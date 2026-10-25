package com.yuki.rubinco.luminite.ingest;

import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.entity.M_ProgressDefinition;
import com.yuki.rubinco.worldprogress.entity.T_WorldImportRun;
import com.yuki.rubinco.worldprogress.entity.T_WorldSnapshot;
import com.yuki.rubinco.worldprogress.ingest.StableSourceReader;
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
import java.util.TreeMap;
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
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Copies the live world into memory, validates it and records a snapshot only when the stored
 * contents changed. The game rewrites the file on every save (clock, weather), so the file hash
 * alone would create a snapshot per autosave; the content hash ignores those values.
 */
@Service
public class TerrariaImportService {
  public static final String GAME_ID = "terraria";
  private static final Logger log = LoggerFactory.getLogger(TerrariaImportService.class);
  private static final List<String> SUCCESSFUL_RUNS = List.of("SUCCEEDED", "UNCHANGED");

  private final TerrariaImportProperties properties;
  private final WorldImportRunLog runLog;
  private final TerrariaWorldStore store;
  private final M_GameWorldRepository worlds;
  private final T_WorldSnapshotRepository snapshots;
  private final T_WorldImportRunRepository runs;
  private final M_ProgressDefinitionRepository definitions;
  private final ObjectMapper objectMapper;
  private final ObjectMapper hashMapper = JsonMapper.builder()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private final TerrariaWorldParser parser;
  private final ReentrantLock importLock = new ReentrantLock();
  private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "terraria-parse");
    thread.setDaemon(true);
    return thread;
  });

  public TerrariaImportService(TerrariaImportProperties properties, WorldImportRunLog runLog,
      TerrariaWorldStore store, M_GameWorldRepository worlds, T_WorldSnapshotRepository snapshots,
      T_WorldImportRunRepository runs, M_ProgressDefinitionRepository definitions,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.runLog = runLog;
    this.store = store;
    this.worlds = worlds;
    this.snapshots = snapshots;
    this.runs = runs;
    this.definitions = definitions;
    this.objectMapper = objectMapper;
    this.parser = new TerrariaWorldParser(properties.getMaxFileSize().toBytes());
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
      run.setParserVersion(TerrariaWorldParser.PARSER_VERSION);
      if (properties.getWorldFile() == null) {
        return runLog.finish(run, "FAILED", "SOURCE_NOT_CONFIGURED", "ワールドファイルの場所が設定されていません。");
      }
      return importWorld(run, world, scheduled);
    } catch (NoSuchFileException exception) {
      return runLog.finish(run, "FAILED", "SOURCE_MISSING", "ワールドファイルが見つかりません。");
    } catch (AccessDeniedException exception) {
      return runLog.finish(run, "FAILED", "SOURCE_UNREADABLE", "ワールドファイルを読み取る権限がありません。");
    } catch (IOException exception) {
      return runLog.finish(run, "FAILED", "SOURCE_READ_FAILED", "ワールドファイルを読み取れませんでした。");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return runLog.finish(run, "FAILED", "INTERRUPTED", "取り込みが中断されました。");
    } catch (RuntimeException exception) {
      log.error("Terraria import failed. runId={}, failureType={}",
          run.getId(), exception.getClass().getSimpleName(), exception);
      return runLog.finish(run, "FAILED", "INTERNAL_ERROR", "取り込み処理でエラーが発生しました。");
    }
  }

  private T_WorldImportRun importWorld(T_WorldImportRun run, M_GameWorld world, boolean scheduled)
      throws IOException, InterruptedException {
    var source = StableSourceReader.read(properties.getWorldFile(), new StableSourceReader.Options(
        properties.getMaxFileSize().toBytes(), properties.getReadAttempts(),
        properties.getRetryDelay(), properties.getQuietPeriod()),
        attributes -> scheduled && unchangedSinceLastRun(world, attributes));
    run.setSourceSize(source.size());
    run.setSourceModifiedAt(source.modifiedAt());
    if (source.unchanged()) return runLog.finish(run, "UNCHANGED", null, null);
    if (!source.ok()) return runLog.finish(run, "FAILED", source.failureCode(), source.failureMessage());
    OffsetDateTime capturedAt = OffsetDateTime.now();

    TerrariaWorld parsed;
    try {
      parsed = parseWithTimeout(source.bytes());
    } catch (TerrariaFormatException exception) {
      return runLog.finish(run, "FAILED", "PARSE_FAILED", "解析できない形式です: " + exception.getMessage());
    } catch (TimeoutException exception) {
      return runLog.finish(run, "FAILED", "PARSE_TIMEOUT", "解析が制限時間を超えました。");
    }
    run.setSourceSha256(parsed.sourceSha256());
    run.setContainerVersion(parsed.version());

    T_WorldSnapshot latest = world.getLatestSnapshotId() == null ? null
        : snapshots.findById(world.getLatestSnapshotId()).orElse(null);
    Map<String, Object> latestJson = latest == null ? Map.of() : read(latest.getExtractedJson());
    Object latestGuid = nested(latestJson, "world", "worldGuid");
    if (latestGuid != null && !latestGuid.equals(parsed.header().worldGuid())) {
      return runLog.finish(run, "FAILED", "WORLD_MISMATCH",
          "登録中のワールドと異なるワールドです。新しいワールドなら管理画面で世代を切り替えてください。");
    }
    String contentHash = contentHash(parsed);
    if (latest != null && TerrariaWorldParser.PARSER_VERSION.equals(latest.getParserVersion())
        && contentHash.equals(latestJson.get("contentHash"))) {
      run.setWorldSnapshotId(latest.getId());
      return runLog.finish(run, "UNCHANGED", null, null);
    }

    TerrariaSummary summary = TerrariaSummary.from(parsed, contentHash);
    // Totals counted under an older storage rule are not comparable: re-baseline without logging.
    boolean itemBaseline = latest == null || !currentStorageRule(latestJson);
    var stored = store.store(world.getId(), latest == null, itemBaseline, parsed,
        new WorldSnapshotRecorder.SnapshotInput(parsed.sourceSha256(), parsed.version(),
            TerrariaWorldParser.PARSER_VERSION, capturedAt, source.modifiedAt(), null,
            objectMapper.writeValueAsString(summary), states(summary.flags())));
    if (stored.isEmpty()) return runLog.finish(run, "UNCHANGED", null, null);
    run.setWorldSnapshotId(stored.get().snapshotId());
    log.info("Terraria snapshot recorded. runId={}, snapshotId={}, progressEvents={}, itemChanges={}, killChanges={}",
        run.getId(), run.getWorldSnapshotId(), stored.get().progressEvents(),
        stored.get().itemChanges(), stored.get().killChanges());
    return runLog.finish(run, "SUCCEEDED", null, null);
  }

  /** Every supported definition names a saved flag; a flag the save lacks stays UNKNOWN. */
  Map<String, ProgressState> states(Map<String, Boolean> flags) {
    Map<String, ProgressState> result = new HashMap<>();
    for (M_ProgressDefinition definition : definitions.findByGameIdOrderBySortOrderAscIdAsc(GAME_ID)) {
      if (!definition.isSupported() || !"WORLD_FLAG".equals(definition.getSourceKind())) continue;
      Boolean value = flags.get(definition.getSourceNote());
      result.put(definition.getProgressKey(), value == null ? ProgressState.UNKNOWN
          : value ? ProgressState.ACHIEVED : ProgressState.NOT_ACHIEVED);
    }
    return result;
  }

  /** Hash of everything shown on the site except the clock and weather at save time. */
  String contentHash(TerrariaWorld parsed) {
    TerrariaSummary summary = TerrariaSummary.from(parsed, null);
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("summary", new TerrariaSummary(null, summary.world(), null, summary.events(),
        summary.flags(), summary.tiles(), summary.townNpcs(), summary.pylons(), summary.totals()));
    content.put("containers", parsed.containers());
    content.put("signs", parsed.signs());
    content.put("kills", new TreeMap<>(parsed.bestiaryKills()));
    content.put("sighted", parsed.bestiarySighted().stream().sorted().toList());
    content.put("chatted", parsed.bestiaryChatted().stream().sorted().toList());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(hashMapper.writeValueAsBytes(content)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** Whether a snapshot's totals were counted under the current managed-storage rule. */
  private boolean currentStorageRule(Map<String, Object> summaryJson) {
    return Integer.valueOf(TerrariaStorage.RULE_VERSION).equals(nested(summaryJson, "totals", "storageRule"));
  }

  private boolean unchangedSinceLastRun(M_GameWorld world, BasicFileAttributes attributes) {
    if (world.getLatestSnapshotId() == null) return false;
    return runs.findFirstByGameWorldIdAndStatusInOrderByFinishedAtDescIdDesc(world.getId(), SUCCESSFUL_RUNS)
        .filter(previous -> TerrariaWorldParser.PARSER_VERSION.equals(previous.getParserVersion()))
        .filter(previous -> Long.valueOf(attributes.size()).equals(previous.getSourceSize()))
        .filter(previous -> previous.getSourceModifiedAt() != null
            && previous.getSourceModifiedAt().toInstant()
                .equals(StableSourceReader.time(attributes).toInstant()))
        .isPresent();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> read(String json) {
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (RuntimeException exception) {
      return Map.of();
    }
  }

  private static Object nested(Map<String, Object> json, String outer, String inner) {
    return json.get(outer) instanceof Map<?, ?> map ? map.get(inner) : null;
  }

  private TerrariaWorld parseWithTimeout(byte[] bytes) throws TimeoutException, InterruptedException {
    Future<TerrariaWorld> future = parseExecutor.submit(() -> parser.parse(bytes));
    try {
      return future.get(properties.getParseTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw exception;
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof TerrariaFormatException format) throw format;
      if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(exception.getCause());
    }
  }

  @PreDestroy
  void shutdown() {
    parseExecutor.shutdownNow();
  }
}
