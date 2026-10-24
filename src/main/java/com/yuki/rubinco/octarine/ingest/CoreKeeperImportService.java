package com.yuki.rubinco.octarine.ingest;

import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.entity.M_ProgressDefinition;
import com.yuki.rubinco.worldprogress.entity.T_WorldImportRun;
import com.yuki.rubinco.worldprogress.entity.T_WorldSnapshot;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import com.yuki.rubinco.worldprogress.repository.M_ProgressDefinitionRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldImportRunRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldSnapshotRepository;
import com.yuki.rubinco.worldprogress.service.ProgressState;
import com.yuki.rubinco.worldprogress.service.WorldImportRunLog;
import com.yuki.rubinco.worldprogress.service.WorldSnapshotRecorder;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the live save without stopping the server, copies it into memory, validates the copy and
 * records a snapshot. Any failure leaves the latest valid snapshot in place.
 */
@Service
public class CoreKeeperImportService {
  public static final String GAME_ID = "corekeeper";
  private static final Logger log = LoggerFactory.getLogger(CoreKeeperImportService.class);
  private static final List<String> SUCCESSFUL_RUNS = List.of("SUCCEEDED", "UNCHANGED");

  private final CoreKeeperImportProperties properties;
  private final WorldImportRunLog runLog;
  private final WorldSnapshotRecorder recorder;
  private final M_GameWorldRepository worlds;
  private final T_WorldSnapshotRepository snapshots;
  private final T_WorldImportRunRepository runs;
  private final M_ProgressDefinitionRepository definitions;
  private final ObjectMapper objectMapper;
  private final CoreKeeperWorldParser parser;
  private final ReentrantLock importLock = new ReentrantLock();
  private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "corekeeper-parse");
    thread.setDaemon(true);
    return thread;
  });

  public CoreKeeperImportService(CoreKeeperImportProperties properties, WorldImportRunLog runLog,
      WorldSnapshotRecorder recorder, M_GameWorldRepository worlds,
      T_WorldSnapshotRepository snapshots, T_WorldImportRunRepository runs,
      M_ProgressDefinitionRepository definitions, ObjectMapper objectMapper) {
    this.properties = properties;
    this.runLog = runLog;
    this.recorder = recorder;
    this.worlds = worlds;
    this.snapshots = snapshots;
    this.runs = runs;
    this.definitions = definitions;
    this.objectMapper = objectMapper;
    this.parser = new CoreKeeperWorldParser(properties.getMaxCompressedSize().toBytes(),
        properties.getMaxDecompressedSize().toBytes(), objectMapper);
  }

  /** Skipped (empty) while another import of this world is still running. */
  public Optional<T_WorldImportRun> runScheduled() {
    if (!importLock.tryLock()) return Optional.empty();
    try {
      return Optional.of(execute(runLog.startScheduled(GAME_ID), true));
    } finally {
      importLock.unlock();
    }
  }

  public Optional<T_WorldImportRun> runNextQueued() {
    if (!importLock.tryLock()) return Optional.empty();
    try {
      return runLog.claimQueued(GAME_ID).map(run -> execute(run, false));
    } finally {
      importLock.unlock();
    }
  }

  private T_WorldImportRun execute(T_WorldImportRun run, boolean scheduled) {
    try {
      M_GameWorld world = worlds.findFirstByGameIdAndActiveTrue(GAME_ID).orElse(null);
      if (world == null) {
        return runLog.finish(run, "FAILED", "NO_ACTIVE_WORLD", "有効なワールド世代が登録されていません。");
      }
      run.setGameWorldId(world.getId());
      run.setParserVersion(CoreKeeperWorldParser.PARSER_VERSION);
      return importWorld(run, world, scheduled);
    } catch (NoSuchFileException exception) {
      return runLog.finish(run, "FAILED", "SOURCE_MISSING", "保存ファイルが見つかりません。");
    } catch (AccessDeniedException exception) {
      return runLog.finish(run, "FAILED", "SOURCE_UNREADABLE", "保存ファイルを読み取る権限がありません。");
    } catch (IOException exception) {
      return runLog.finish(run, "FAILED", "SOURCE_READ_FAILED", "保存ファイルを読み取れませんでした。");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return runLog.finish(run, "FAILED", "INTERRUPTED", "取り込みが中断されました。");
    } catch (RuntimeException exception) {
      log.error("Core Keeper import failed. runId={}, failureType={}",
          run.getId(), exception.getClass().getSimpleName(), exception);
      return runLog.finish(run, "FAILED", "INTERNAL_ERROR", "取り込み処理でエラーが発生しました。");
    }
  }

  private T_WorldImportRun importWorld(T_WorldImportRun run, M_GameWorld world, boolean scheduled)
      throws IOException, InterruptedException {
    var file = properties.getWorldFile();
    long maxBytes = properties.getMaxCompressedSize().toBytes();
    byte[] bytes = null;
    BasicFileAttributes before = null;
    for (int attempt = 1; attempt <= properties.getReadAttempts() && bytes == null; attempt++) {
      if (attempt > 1) Thread.sleep(properties.getRetryDelay().toMillis());
      before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()) {
        return runLog.finish(run, "FAILED", "SOURCE_INVALID", "保存ファイルが通常ファイルではありません。");
      }
      run.setSourceSize(before.size());
      run.setSourceModifiedAt(time(before));
      if (before.size() > maxBytes) {
        return runLog.finish(run, "FAILED", "SOURCE_TOO_LARGE", "保存ファイルがサイズ上限を超えています。");
      }
      if (scheduled && attempt == 1 && unchangedSinceLastRun(world, before)) {
        return runLog.finish(run, "UNCHANGED", null, null);
      }
      if (time(before).isAfter(OffsetDateTime.now().minus(properties.getQuietPeriod()))) {
        continue;
      }
      byte[] copy = read(file, maxBytes);
      BasicFileAttributes after =
          Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (copy.length == before.size() && after.size() == before.size()
          && after.lastModifiedTime().equals(before.lastModifiedTime())) {
        bytes = copy;
      }
    }
    if (bytes == null) {
      return runLog.finish(run, "FAILED", "SOURCE_CHANGING",
          "保存処理と重なった可能性があるため、今回の取り込みを見送りました。");
    }
    OffsetDateTime capturedAt = OffsetDateTime.now();

    CoreKeeperWorld parsed;
    try {
      parsed = parseWithTimeout(bytes);
    } catch (CoreKeeperFormatException exception) {
      return runLog.finish(run, "FAILED", "PARSE_FAILED", "解析できない形式です: " + exception.getMessage());
    } catch (TimeoutException exception) {
      return runLog.finish(run, "FAILED", "PARSE_TIMEOUT", "解析が制限時間を超えました。");
    }
    run.setSourceSha256(parsed.sourceSha256());
    run.setDecompressedBytes(parsed.decompressedBytes());
    run.setContainerVersion(parsed.containerVersion());

    Optional<T_WorldSnapshot> existing = snapshots.findByGameWorldIdAndSourceSha256AndParserVersion(
        world.getId(), parsed.sourceSha256(), CoreKeeperWorldParser.PARSER_VERSION);
    if (existing.isPresent()) {
      run.setWorldSnapshotId(existing.get().getId());
      return runLog.finish(run, "UNCHANGED", null, null);
    }
    Long previousSeed = latestSeed(world);
    if (previousSeed != null && parsed.globalSeed() != null && !previousSeed.equals(parsed.globalSeed())) {
      return runLog.finish(run, "FAILED", "WORLD_MISMATCH",
          "登録中のワールドとシードが異なります。新しいワールドなら管理画面で世代を切り替えてください。");
    }

    var recorded = recorder.record(world.getId(), new WorldSnapshotRecorder.SnapshotInput(
        parsed.sourceSha256(), parsed.containerVersion(), CoreKeeperWorldParser.PARSER_VERSION,
        capturedAt, time(before), parsed.generationJson(), extractedJson(parsed),
        states(parsed)));
    if (recorded.isEmpty()) return runLog.finish(run, "UNCHANGED", null, null);
    run.setWorldSnapshotId(recorded.get().snapshot().getId());
    log.info("Core Keeper snapshot recorded. runId={}, snapshotId={}, events={}",
        run.getId(), run.getWorldSnapshotId(), recorded.get().events().size());
    return runLog.finish(run, "SUCCEEDED", null, null);
  }

  /** Supported items map to save fields; the full record list makes absence a real "not yet". */
  Map<String, ProgressState> states(CoreKeeperWorld parsed) {
    Map<String, ProgressState> result = new HashMap<>();
    for (M_ProgressDefinition definition : definitions.findByGameIdOrderBySortOrderAscIdAsc(GAME_ID)) {
      if (!definition.isSupported()) continue;
      ProgressState state = switch (definition.getSourceKind()) {
        case "GREAT_WALL_STATUS" -> achieved(parsed.greatWallLowered());
        case "KILLED_OBJECT" -> definition.getSourceObjectId() == null ? ProgressState.UNKNOWN
            : achieved(parsed.hasRecordedObject(definition.getSourceObjectId()));
        default -> ProgressState.UNKNOWN;
      };
      result.put(definition.getProgressKey(), state);
    }
    return result;
  }

  private static ProgressState achieved(boolean value) {
    return value ? ProgressState.ACHIEVED : ProgressState.NOT_ACHIEVED;
  }

  private boolean unchangedSinceLastRun(M_GameWorld world, BasicFileAttributes attributes) {
    if (world.getLatestSnapshotId() == null) return false;
    return runs.findFirstByGameWorldIdAndStatusInOrderByFinishedAtDescIdDesc(world.getId(), SUCCESSFUL_RUNS)
        .filter(previous -> CoreKeeperWorldParser.PARSER_VERSION.equals(previous.getParserVersion()))
        .filter(previous -> Long.valueOf(attributes.size()).equals(previous.getSourceSize()))
        .filter(previous -> previous.getSourceModifiedAt() != null
            && previous.getSourceModifiedAt().toInstant().equals(time(attributes).toInstant()))
        .isPresent();
  }

  private Long latestSeed(M_GameWorld world) {
    if (world.getLatestSnapshotId() == null) return null;
    return snapshots.findById(world.getLatestSnapshotId()).map(snapshot -> {
      try {
        var seed = objectMapper.readTree(snapshot.getExtractedJson()).get("globalSeed");
        return seed != null && seed.isIntegralNumber() ? seed.asLong() : null;
      } catch (RuntimeException exception) {
        return null;
      }
    }).orElse(null);
  }

  private String extractedJson(CoreKeeperWorld parsed) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("globalSeed", parsed.globalSeed());
    json.put("greatWallLowered", parsed.greatWallLowered());
    json.put("bossKilledFromGameLogic", new java.util.TreeMap<>(parsed.bossKilledFromGameLogic()));
    json.put("recordedObjects", parsed.recordedObjects());
    json.put("notes", List.of("amount is a stored game value, not a verified kill or mining count."));
    return objectMapper.writeValueAsString(json);
  }

  private CoreKeeperWorld parseWithTimeout(byte[] bytes)
      throws TimeoutException, InterruptedException {
    Future<CoreKeeperWorld> future = parseExecutor.submit(() -> parser.parse(bytes));
    try {
      return future.get(properties.getParseTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw exception;
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof CoreKeeperFormatException format) throw format;
      if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(exception.getCause());
    }
  }

  private static byte[] read(java.nio.file.Path file, long maxBytes) throws IOException {
    try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
      if (bytes.length > maxBytes) throw new IOException("Source grew past its limit");
      return bytes;
    }
  }

  private static OffsetDateTime time(BasicFileAttributes attributes) {
    // Millisecond precision survives every supported database column type.
    return attributes.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS)
        .atOffset(ZoneOffset.UTC);
  }

  @PreDestroy
  void shutdown() {
    parseExecutor.shutdownNow();
  }
}
