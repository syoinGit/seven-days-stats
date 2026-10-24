package com.yuki.rubinco.watchpoint.ingest;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SevenDaysLogImportRunner {
  private static final String GAME_ID = "7dtd";

  private final SevenDaysDataProperties properties;
  private final GameLogImportService logImportService;
  private final ServerActivityGate gate;

  @Scheduled(
      initialDelayString = "${app.sevendays.log.initial-delay:10m}",
      fixedDelayString = "${app.sevendays.log.fixed-delay:10m}")
  public void scheduledLogImport() {
    if (!"file".equalsIgnoreCase(properties.mode()) || !properties.log().scheduledEnabled()
        || !gate.importAllowed("7dtd-log-import", GAME_ID)) {
      return;
    }
    log.info("7DTD log import started.");
    GameLogImportResult result = logImportService.importLogs();
    log.info("7DTD log import finished. {}", result);
  }
}
