package com.yuki.rubinco.portal.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server control boundary. The mock uses stored status; Docker reports live container status. */
public interface GameServerStartGateway {
  StartResult requestStart(String gameId, String requestedBy);

  /** Accepted stops may finish asynchronously; the result then reports {@code stopping}. */
  default StartResult requestStop(String gameId, String requestedBy) {
    return new StartResult(false, "error", "このサーバーはサイトから停止できません。");
  }

  default String currentStatus(String gameId) {
    return null;
  }

  default boolean liveStatus() {
    return false;
  }

  default Map<String, String> currentStatuses(List<String> gameIds) {
    Map<String, String> statuses = new LinkedHashMap<>();
    gameIds.forEach(id -> statuses.put(id, currentStatus(id)));
    return statuses;
  }

  default Map<String, String> freshStatuses(List<String> gameIds) {
    return currentStatuses(gameIds);
  }

  /** Short messages about recent control failures, keyed by game id. */
  default Map<String, String> controlNotices() {
    return Map.of();
  }

  record StartResult(boolean accepted, String status, String message) { }
}
