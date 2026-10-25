package com.yuki.rubinco.octarine.session;

import com.yuki.rubinco.octarine.ingest.CoreKeeperImportProperties;
import com.yuki.rubinco.octarine.ingest.CoreKeeperImportService;
import com.yuki.rubinco.portal.service.ServerActivityGate;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ends sessions left open when the server stops (players get no disconnect line then). */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreKeeperSessionRunner {
  private final CoreKeeperImportProperties properties;
  private final CoreKeeperSessionStore store;
  private final ServerActivityGate gate;

  @Scheduled(fixedDelayString = "${app.corekeeper.session-check-interval:1m}",
      initialDelayString = "${app.corekeeper.session-check-interval:1m}")
  public void closeWhenStopped() {
    if (!properties.isLogStreamEnabled() || !gate.stopped(CoreKeeperImportService.GAME_ID)) return;
    int closed = store.closeOpen(OffsetDateTime.now(), CoreKeeperSessionStore.SERVER_STOPPED);
    if (closed > 0) log.info("Closed Core Keeper sessions after the server stopped. count={}", closed);
  }
}
