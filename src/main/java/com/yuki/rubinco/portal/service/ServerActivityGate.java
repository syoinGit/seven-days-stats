package com.yuki.rubinco.portal.service;

import com.yuki.rubinco.portal.config.GameCatalogProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pauses scheduled work for a game while its server is stopped. Imports still run once after the
 * server is first seen stopped, so the final save is read; AI posts only run while it is online.
 *
 * <p>Without live status (the mock provider, local runs) everything is allowed. A status that
 * cannot be read ("error") does not block imports, but does block AI posts.
 */
@Component
public class ServerActivityGate {
  private static final Logger log = LoggerFactory.getLogger(ServerActivityGate.class);
  private static final List<String> RUNNING = List.of("online", "starting", "stopping");

  private final GameServerStartGateway gateway;
  private final GameCatalogProperties catalog;
  // Per consumer: true once the stopped server's final save has been handed to it.
  private final Map<String, Boolean> caughtUp = new ConcurrentHashMap<>();

  public ServerActivityGate(GameServerStartGateway gateway, GameCatalogProperties catalog) {
    this.gateway = gateway;
    this.catalog = catalog;
  }

  /** {@code consumer} names the scheduled job; each job gets its own catch-up run. */
  public boolean importAllowed(String consumer, String gameId) {
    String status = status(gameId);
    if (status == null || RUNNING.contains(status)) {
      caughtUp.put(consumer, false);
      return true;
    }
    if (!"offline".equals(status)) return true;
    if (Boolean.TRUE.equals(caughtUp.put(consumer, true))) return false;
    log.info("Server is stopped; running one catch-up import before pausing. consumer={}, gameId={}",
        consumer, gameId);
    return true;
  }

  public boolean aiAllowed(String gameId) {
    String status = status(gameId);
    return status == null || "online".equals(status);
  }

  private String status(String gameId) {
    if (!gateway.liveStatus()) return null;
    List<String> ids = catalog.getCatalog().stream().map(GameCatalogProperties.Game::getId).toList();
    return gateway.currentStatuses(ids).get(gameId);
  }
}
