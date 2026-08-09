package com.yuki.sevendays_states.batch;

import com.yuki.sevendays_states.service.SurvivorKarenPublishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SurvivorKarenPublishingRunner {

  private final SurvivorKarenPublishingService publishingService;

  /** Hourly checks let a restarted server catch up while the date-based source key prevents duplicates. */
  @Scheduled(cron = "${app.survivor-karen.schedule-cron:0 17 * * * *}", zone = "Asia/Tokyo")
  public void publishDailyPost() {
    try {
      var result = publishingService.publishIfDue();
      if (result.status() == SurvivorKarenPublishingService.PublishStatus.PUBLISHED) {
        log.info("Survivor Karen post published. date={}, theme={}, image={}",
            result.date(), result.theme(), result.imageAttached());
      }
    } catch (RuntimeException exception) {
      log.error("Survivor Karen daily publishing failed; the next hourly check will retry.", exception);
    }
  }
}
