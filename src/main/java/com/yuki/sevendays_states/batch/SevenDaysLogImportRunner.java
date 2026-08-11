package com.yuki.sevendays_states.batch;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import com.yuki.sevendays_states.service.GameLogImportResult;
import com.yuki.sevendays_states.service.GameLogImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SevenDaysLogImportRunner {

  private final SevenDaysDataProperties properties;
  private final GameLogImportService logImportService;

  @Scheduled(
      initialDelayString = "${app.sevendays.log.initial-delay:10m}",
      fixedDelayString = "${app.sevendays.log.fixed-delay:10m}")
  public void scheduledLogImport() {
    if (!"file".equalsIgnoreCase(properties.mode()) || !properties.log().scheduledEnabled()) {
      return;
    }
    log.info("7DTD log import started.");
    GameLogImportResult result = logImportService.importLogs();
    log.info("7DTD log import finished. {}", result);
  }
}
