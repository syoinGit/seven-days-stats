package com.yuki.rubinco.octarine.ingest;

import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.ingest.StableSourceReader;
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
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

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
  private final CoreKeeperWorldStore store;
  private final M_GameWorldRepository worlds;
  private final T_WorldSnapshotRepository snapshots;
  private final T_WorldImportRunRepository runs;
  private final M_ProgressDefinitionRepository definitions;
  private final ObjectMapper objectMapper;
  private final ObjectMapper hashMapper = JsonMapper.builder()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private final CoreKeeperWorldParser parser;
  private final ReentrantLock importLock = new ReentrantLock();
  private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "corekeeper-parse");
    thread.setDaemon(true);
    return thread;
  });

  public CoreKeeperImportService(CoreKeeperImportProperties properties, WorldImportRunLog runLog,
      CoreKeeperWorldStore store, M_GameWorldRepository worlds,
      T_WorldSnapshotRepository snapshots, T_WorldImportRunRepository runs,
      M_ProgressDefinitionRepository definitions, ObjectMapper objectMapper) {
    this.properties = properties;
    this.runLog = runLog;
    this.store = store;
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
    var source = StableSourceReader.read(properties.getWorldFile(), new StableSourceReader.Options(
        properties.getMaxCompressedSize().toBytes(), properties.getReadAttempts(),
        properties.getRetryDelay(), properties.getQuietPeriod()),
        attributes -> scheduled && unchangedSinceLastRun(world, attributes));
    run.setSourceSize(source.size());
    run.setSourceModifiedAt(source.modifiedAt());
    if (source.unchanged()) return runLog.finish(run, "UNCHANGED", null, null);
    if (!source.ok()) return runLog.finish(run, "FAILED", source.failureCode(), source.failureMessage());
    byte[] bytes = source.bytes();
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
    T_WorldSnapshot latest = world.getLatestSnapshotId() == null ? null
        : snapshots.findById(world.getLatestSnapshotId()).orElse(null);
    JsonNode latestJson = latest == null ? null : readTree(latest.getExtractedJson());
    Long previousSeed = latestJson != null && latestJson.path("globalSeed").isIntegralNumber()
        ? latestJson.get("globalSeed").asLong() : null;
    if (previousSeed != null && parsed.globalSeed() != null && !previousSeed.equals(parsed.globalSeed())) {
      return runLog.finish(run, "FAILED", "WORLD_MISMATCH",
          "登録中のワールドとシードが異なります。新しいワールドなら管理画面で世代を切り替えてください。");
    }
    // The game saves every few minutes; creatures moving alone must not create a snapshot.
    String contentHash = contentHash(parsed);
    if (latest != null && CoreKeeperWorldParser.PARSER_VERSION.equals(latest.getParserVersion())
        && contentHash.equals(latestJson == null ? null : latestJson.path("contentHash").asString(null))) {
      run.setWorldSnapshotId(latest.getId());
      return runLog.finish(run, "UNCHANGED", null, null);
    }

    var stored = store.store(world.getId(), latest == null, parsed,
        new WorldSnapshotRecorder.SnapshotInput(parsed.sourceSha256(), parsed.containerVersion(),
            CoreKeeperWorldParser.PARSER_VERSION, capturedAt, source.modifiedAt(),
            parsed.generationJson(), extractedJson(parsed, contentHash), states(parsed)));
    if (stored.isEmpty()) return runLog.finish(run, "UNCHANGED", null, null);
    run.setWorldSnapshotId(stored.get().snapshotId());
    log.info("Core Keeper snapshot recorded. runId={}, snapshotId={}, progressEvents={}, itemChanges={}",
        run.getId(), run.getWorldSnapshotId(), stored.get().progressEvents(), stored.get().itemChanges());
    return runLog.finish(run, "SUCCEEDED", null, null);
  }

  /** Hash of the stored contents shown on the site; creature counts are left out on purpose. */
  String contentHash(CoreKeeperWorld parsed) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("greatWallLowered", parsed.greatWallLowered());
    content.put("recordedObjects", parsed.recordedObjects());
    content.put("containers", parsed.containers());
    content.put("crops", parsed.crops());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(hashMapper.writeValueAsBytes(content)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
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
            && previous.getSourceModifiedAt().toInstant()
                .equals(StableSourceReader.time(attributes).toInstant()))
        .isPresent();
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String extractedJson(CoreKeeperWorld parsed, String contentHash) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("contentHash", contentHash);
    json.put("globalSeed", parsed.globalSeed());
    json.put("greatWallLowered", parsed.greatWallLowered());
    json.put("bossKilledFromGameLogic", new java.util.TreeMap<>(parsed.bossKilledFromGameLogic()));
    json.put("recordedObjects", parsed.recordedObjects());
    var totals = CoreKeeperWorldStore.itemTotals(parsed);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("containers", parsed.containers().stream()
        .filter(container -> "STORAGE".equals(container.holder())).count());
    summary.put("distinctItems", totals.size());
    summary.put("storedSlots", totals.values().stream().mapToInt(CoreKeeperWorldStore.ItemTotal::slots).sum());
    summary.put("entities", parsed.objects().stream().mapToInt(CoreKeeperWorld.ObjectTally::count).sum());
    summary.put("creatures", count(parsed, "CREATURE"));
    summary.put("crops", parsed.crops().stream().mapToInt(CoreKeeperWorld.CropTally::count).sum());
    summary.put("skippedEntities", parsed.skippedEntities());
    json.put("summary", summary);
    json.put("notes", List.of("amount is a stored game value, not a verified kill or mining count.",
        "container amounts are stack sizes for materials and durability for equipment."));
    return objectMapper.writeValueAsString(json);
  }

  private static int count(CoreKeeperWorld parsed, String category) {
    return parsed.objects().stream().filter(tally -> category.equals(tally.category()))
        .mapToInt(CoreKeeperWorld.ObjectTally::count).sum();
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

  @PreDestroy
  void shutdown() {
    parseExecutor.shutdownNow();
  }
}
