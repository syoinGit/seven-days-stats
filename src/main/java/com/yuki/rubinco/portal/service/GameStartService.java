package com.yuki.rubinco.portal.service;

import com.yuki.rubinco.portal.entity.M_GamePortalState;
import com.yuki.rubinco.portal.repository.M_GamePortalStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GameStartService {
  private static final Logger log = LoggerFactory.getLogger(GameStartService.class);
  private static final Duration REQUEST_COOLDOWN = Duration.ofSeconds(10);
  private static final Set<String> VALID_STATUSES = Set.of(
      "offline", "starting", "online", "stopping", "maintenance", "error");

  private final GamePortalService portalService;
  private final M_GamePortalStateRepository stateRepository;
  private final GameServerStartGateway gateway;
  private final Clock clock = Clock.systemUTC();
  private final GameMemoryBudget memoryBudget;
  private final Map<String, Instant> recentRequests = new ConcurrentHashMap<>();
  private final Set<String> requestsInFlight = ConcurrentHashMap.newKeySet();

  @Transactional
  @PreAuthorize("@gameServerAccess.currentUserCanStart()")
  public GameServerStartGateway.StartResult requestStart(String gameId, String requestedBy) {
    String safeActor = safeLogValue(requestedBy);
    if (!portalService.supports(gameId)) {
      return audited(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, "error", "対象ゲームが見つかりません。"));
    }
    if (!portalService.canStart(gameId)) {
      return audited(gameId, safeActor, new GameServerStartGateway.StartResult(
          false, "error", "このサーバーは自動起動のため、サイトからの起動対象ではありません。"));
    }
    LockedStatuses locked = lockStatuses(gameId);
    if (locked == null) {
      return audited(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, "error", "サーバー状態を確認できません。"));
    }
    M_GamePortalState currentState = locked.current();
    Map<String, String> statuses = locked.statuses();
    String currentStatus = statuses.get(gameId);
    if (!"offline".equals(currentStatus)) {
      return audited(gameId, safeActor, unavailable(currentStatus));
    }

    var allocation = memoryBudget.evaluate(statuses).allocations().get(gameId);
    if (allocation == null || !allocation.startAllowed()) {
      String reason = allocation == null ? "メモリ枠の設定を確認できません。" : allocation.reason();
      return audited(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, "offline", reason));
    }

    Instant now = clock.instant();
    StartSlot startSlot = reserveSlot(gameId, "start", now);
    if (startSlot != StartSlot.RESERVED) {
      String status = startSlot == StartSlot.IN_FLIGHT ? "starting" : currentStatus;
      String message = startSlot == StartSlot.IN_FLIGHT
          ? "別の起動要求を処理中です。しばらくお待ちください。"
          : "起動要求の連続送信を制限しています。しばらくお待ちください。";
      return audited(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, status, message));
    }

    try {
      GameServerStartGateway.StartResult gatewayResult;
      try {
        gatewayResult = gateway.requestStart(gameId, safeActor);
      } catch (RuntimeException exception) {
        log.error("Game start gateway failed. gameId={}, failureType={}",
            safeLogValue(gameId), exception.getClass().getSimpleName());
        return audited(gameId, safeActor, new GameServerStartGateway.StartResult(
            false, "error", "起動要求を処理できませんでした。時間を置いて再試行してください。"));
      }

      GameServerStartGateway.StartResult result = normalize(gatewayResult, currentStatus);
      if (result.accepted()) {
        currentState.setServerStatus("starting");
        stateRepository.save(currentState);
      }
      return audited(gameId, safeActor, result);
    } finally {
      requestsInFlight.remove(gameId);
    }
  }

  /** Stops release memory and never need a budget check, but share the start lock order. */
  @Transactional
  @PreAuthorize("@gameServerAccess.currentUserCanStart()")
  public GameServerStartGateway.StartResult requestStop(String gameId, String requestedBy) {
    String safeActor = safeLogValue(requestedBy);
    if (!portalService.supports(gameId)) {
      return auditedStop(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, "error", "対象ゲームが見つかりません。"));
    }
    if (!portalService.canStart(gameId)) {
      return auditedStop(gameId, safeActor, new GameServerStartGateway.StartResult(
          false, "error", "このサーバーは自動起動のため、サイトからの停止対象ではありません。"));
    }
    LockedStatuses locked = lockStatuses(gameId);
    if (locked == null) {
      return auditedStop(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, "error", "サーバー状態を確認できません。"));
    }
    String currentStatus = locked.statuses().get(gameId);
    if (!"online".equals(currentStatus) && !"starting".equals(currentStatus)) {
      String safeStatus = isValidStatus(currentStatus) ? currentStatus : "error";
      String message = switch (safeStatus) {
        case "offline" -> "サーバーはすでに停止しています。";
        case "stopping" -> "停止処理中です。しばらくお待ちください。";
        case "maintenance" -> "メンテナンス中のため停止できません。";
        default -> "現在の状態ではサーバーを停止できません。";
      };
      return auditedStop(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, safeStatus, message));
    }

    StartSlot slot = reserveSlot(gameId, "stop", clock.instant());
    if (slot != StartSlot.RESERVED) {
      String message = slot == StartSlot.IN_FLIGHT
          ? "別の操作を処理中です。しばらくお待ちください。"
          : "停止要求の連続送信を制限しています。しばらくお待ちください。";
      return auditedStop(gameId, safeActor,
          new GameServerStartGateway.StartResult(false, currentStatus, message));
    }

    try {
      GameServerStartGateway.StartResult result;
      try {
        result = gateway.requestStop(gameId, safeActor);
      } catch (RuntimeException exception) {
        log.error("Game stop gateway failed. gameId={}, failureType={}",
            safeLogValue(gameId), exception.getClass().getSimpleName());
        return auditedStop(gameId, safeActor, new GameServerStartGateway.StartResult(
            false, "error", "停止要求を処理できませんでした。時間を置いて再試行してください。"));
      }
      if (result == null) {
        result = new GameServerStartGateway.StartResult(
            false, "error", "停止サービスから応答がありませんでした。");
      }
      String status = isValidStatus(result.status()) ? result.status()
          : result.accepted() ? "stopping" : currentStatus;
      String message = result.message() == null || result.message().isBlank()
          ? "停止要求を処理しました。" : result.message().strip();
      if (result.accepted()) {
        // Only the stored (mock) status depends on this; Docker reports the live state.
        locked.current().setServerStatus("offline");
        stateRepository.save(locked.current());
      }
      return auditedStop(gameId, safeActor,
          new GameServerStartGateway.StartResult(result.accepted(), status, message));
    } finally {
      requestsInFlight.remove(gameId);
    }
  }

  /** Locks every state row in one order so concurrent operations on any game serialize. */
  private LockedStatuses lockStatuses(String gameId) {
    var lockedStates = stateRepository.findAllForUpdate();
    M_GamePortalState currentState = lockedStates.stream()
        .filter(state -> gameId.equals(state.getGameId())).findFirst().orElse(null);
    if (currentState == null) {
      log.error("Game portal state is missing. gameId={}", safeLogValue(gameId));
      return null;
    }
    Map<String, String> statuses = new LinkedHashMap<>();
    lockedStates.forEach(state -> statuses.put(state.getGameId(), state.getServerStatus()));
    if (gateway.liveStatus() && !"maintenance".equals(currentState.getServerStatus())) {
      statuses.clear();
      statuses.putAll(gateway.freshStatuses(memoryBudget.gameIds()));
      lockedStates.stream().filter(state -> "maintenance".equals(state.getServerStatus()))
          .forEach(state -> statuses.put(state.getGameId(), "maintenance"));
    }
    return new LockedStatuses(currentState, statuses);
  }

  private record LockedStatuses(M_GamePortalState current, Map<String, String> statuses) { }

  private StartSlot reserveSlot(String gameId, String operation, Instant now) {
    if (!requestsInFlight.add(gameId)) {
      return StartSlot.IN_FLIGHT;
    }
    String key = operation + ":" + gameId;
    Instant previous = recentRequests.get(key);
    if (previous != null && previous.plus(REQUEST_COOLDOWN).isAfter(now)) {
      requestsInFlight.remove(gameId);
      return StartSlot.COOLDOWN;
    }
    recentRequests.put(key, now);
    return StartSlot.RESERVED;
  }

  private GameServerStartGateway.StartResult unavailable(String status) {
    String safeStatus = isValidStatus(status) ? status : "error";
    String message = switch (safeStatus) {
      case "starting" -> "起動要求を処理中です。しばらくお待ちください。";
      case "stopping" -> "停止処理中です。停止後に起動できます。";
      case "online" -> "サーバーはすでに稼働しています。";
      case "maintenance" -> "メンテナンス中のため起動できません。";
      case "error" -> "エラー状態のため、管理者の確認が必要です。";
      default -> "現在の状態ではサーバーを起動できません。";
    };
    return new GameServerStartGateway.StartResult(false, safeStatus, message);
  }

  private GameServerStartGateway.StartResult normalize(
      GameServerStartGateway.StartResult result, String fallbackStatus) {
    if (result == null) {
      return new GameServerStartGateway.StartResult(
          false, "error", "起動サービスから応答がありませんでした。");
    }
    String message = result.message() == null || result.message().isBlank()
        ? "起動要求を処理しました。" : result.message().strip();
    if (result.accepted()) {
      return new GameServerStartGateway.StartResult(true, "starting", message);
    }
    String status = isValidStatus(result.status()) ? result.status() : fallbackStatus;
    return new GameServerStartGateway.StartResult(false, status, message);
  }

  private boolean isValidStatus(String status) {
    return status != null && VALID_STATUSES.contains(status);
  }

  private GameServerStartGateway.StartResult audited(
      String gameId, String requestedBy, GameServerStartGateway.StartResult result) {
    log.info("Game start request evaluated. gameId={}, requestedBy={}, accepted={}, status={}",
        safeLogValue(gameId), requestedBy, result.accepted(), result.status());
    return result;
  }

  private GameServerStartGateway.StartResult auditedStop(
      String gameId, String requestedBy, GameServerStartGateway.StartResult result) {
    log.info("Game stop request evaluated. gameId={}, requestedBy={}, accepted={}, status={}",
        safeLogValue(gameId), requestedBy, result.accepted(), result.status());
    return result;
  }

  private String safeLogValue(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    String normalized = value.strip().replace('\r', '_').replace('\n', '_');
    return normalized.substring(0, Math.min(normalized.length(), 160));
  }

  private enum StartSlot {
    RESERVED,
    IN_FLIGHT,
    COOLDOWN
  }
}
