package com.yuki.rubinco.portal.service;

import com.yuki.rubinco.portal.config.DockerGameServerProperties;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.games", name = "start-provider", havingValue = "docker")
public class DockerGameServerStartGateway implements GameServerStartGateway {
  private static final Logger log = LoggerFactory.getLogger(DockerGameServerStartGateway.class);
  private static final Set<String> MANUAL_GAMES = Set.of("7dtd", "corekeeper", "minecraft");
  private final DockerCommandRunner runner;
  private final ObjectMapper objectMapper;
  private final DockerGameServerProperties properties;
  // A graceful stop outlives the HTTP request, so it runs here while polling reports "stopping".
  private final ExecutorService stopExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<String> stopping = ConcurrentHashMap.newKeySet();
  private volatile Snapshot snapshot;

  private record Snapshot(List<String> gameIds, Map<String, String> statuses, long expiresAt) { }

  @Override
  public synchronized Map<String, String> freshStatuses(List<String> gameIds) {
    snapshot = null;
    return currentStatuses(gameIds);
  }

  @Override
  public boolean liveStatus() {
    return true;
  }

  @Override
  public String currentStatus(String gameId) {
    return withStop(gameId, containerStatus(gameId));
  }

  private String containerStatus(String gameId) {
    try {
      return status(runner.run("status", gameId));
    } catch (RuntimeException exception) {
      log.warn("Docker status unavailable. failureType={}", exception.getClass().getSimpleName());
      return "error";
    }
  }

  @Override
  public synchronized Map<String, String> currentStatuses(List<String> gameIds) {
    Snapshot cached = snapshot;
    if (cached != null && cached.gameIds().equals(gameIds) && System.nanoTime() < cached.expiresAt()) {
      return cached.statuses();
    }
    Map<String, String> statuses = new LinkedHashMap<>();
    gameIds.forEach(id -> statuses.put(id, "error"));
    if (gameIds.isEmpty()) return statuses;
    try {
      for (String line : runner.run("statuses").lines().toList()) {
        int separator = line.indexOf(' ');
        if (separator < 0) continue;
        String gameId = line.substring(0, separator);
        if (statuses.containsKey(gameId)) {
          try {
            statuses.put(gameId, withStop(gameId, status(line.substring(separator + 1))));
          } catch (RuntimeException exception) {
            log.warn("Docker state invalid. gameId={}, failureType={}",
                gameId, exception.getClass().getSimpleName());
          }
        }
      }
    } catch (RuntimeException exception) {
      log.warn("Docker statuses unavailable. failureType={}", exception.getClass().getSimpleName());
    }
    Map<String, String> result = Map.copyOf(statuses);
    snapshot = new Snapshot(List.copyOf(gameIds), result,
        System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    return result;
  }

  private String withStop(String gameId, String status) {
    return stopping.contains(gameId) && !"offline".equals(status) ? "stopping" : status;
  }

  private String status(String json) {
    var state = objectMapper.readTree(json);
    return switch (state.path("Status").asString()) {
      case "created", "exited" -> "offline";
      case "restarting" -> "starting";
      case "running" -> switch (state.path("Health").path("Status").asString("none")) {
        case "starting" -> "starting";
        case "unhealthy" -> "error";
        default -> "online";
      };
      default -> "error";
    };
  }

  @Override
  public StartResult requestStart(String gameId, String requestedBy) {
    if (!MANUAL_GAMES.contains(gameId)) {
      return new StartResult(false, "error", "サイトからの起動対象ではありません。");
    }
    String status = currentStatus(gameId);
    if (!"offline".equals(status)) {
      return new StartResult(false, status, "起動できる停止中のコンテナが見つかりません。");
    }
    runner.run("start", gameId);
    snapshot = null;
    return new StartResult(true, "starting", "起動しました。ゲームに接続できるまで数分かかる場合があります。");
  }

  @Override
  public StartResult requestStop(String gameId, String requestedBy) {
    if (!MANUAL_GAMES.contains(gameId)) {
      return new StartResult(false, "error", "サイトからの停止対象ではありません。");
    }
    String status = containerStatus(gameId);
    if (!"online".equals(status) && !"starting".equals(status)) {
      return new StartResult(false, status, "停止できる稼働中のコンテナが見つかりません。");
    }
    if (!stopping.add(gameId)) {
      return new StartResult(false, "stopping", "停止処理中です。しばらくお待ちください。");
    }
    snapshot = null;
    try {
      stopExecutor.execute(() -> stop(gameId));
    } catch (RejectedExecutionException exception) {
      stopping.remove(gameId);
      return new StartResult(false, status, "停止要求を処理できませんでした。");
    }
    return new StartResult(true, "stopping", "停止を開始しました。保存のため最大2分ほどかかります。");
  }

  private void stop(String gameId) {
    try {
      runner.run(properties.getStopTimeout(), "stop", gameId);
    } catch (RuntimeException exception) {
      log.warn("Docker stop failed. gameId={}, failureType={}",
          gameId, exception.getClass().getSimpleName());
    } finally {
      stopping.remove(gameId);
      snapshot = null;
    }
  }

  @PreDestroy
  void shutdown() {
    // Do not interrupt running stops; interrupting would kill the Docker client mid-save.
    stopExecutor.shutdown();
  }
}
