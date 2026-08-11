package com.yuki.sevendays_states.batch;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import com.yuki.sevendays_states.service.SevenDaysDataImportResult;
import com.yuki.sevendays_states.service.SevenDaysDataImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SevenDaysDataImportRunner implements ApplicationRunner {

  private final SevenDaysDataProperties properties;
  private final SevenDaysDataImportService importService;

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.importSettings().startupEnabled()) {
      return;
    }
    log.info("7DTD startup import started.");
    SevenDaysDataImportResult result = importService.importCurrentData();
    log.info("7DTD startup import finished. {}", result);
  }

  @Scheduled(fixedDelayString = "${app.sevendays.import.fixed-delay:10m}")
  public void scheduledImport() {
    if (!properties.importSettings().scheduledEnabled()) {
      return;
    }
    log.info("7DTD scheduled import started.");
    SevenDaysDataImportResult result = importService.importCurrentData();
    log.info("7DTD scheduled import finished. {}", result);
  }
}
