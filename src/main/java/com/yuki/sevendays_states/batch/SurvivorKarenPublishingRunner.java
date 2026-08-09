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

  /** Frequent checks in the nightly uptime window honour each day's deterministic random slot. */
  @Scheduled(cron = "${app.survivor-karen.schedule-cron:0 */5 20-23 * * *}", zone = "Asia/Tokyo")
  public void publishDailyPost() {
    try {
      var result = publishingService.publishIfDue();
      if (result.status() == SurvivorKarenPublishingService.PublishStatus.PUBLISHED) {
        log.info("Survivor Karen post published. date={}, theme={}, image={}",
            result.date(), result.theme(), result.imageAttached());
      }
    } catch (RuntimeException exception) {
      log.error("Survivor Karen daily publishing failed; the next nightly check will retry.", exception);
    }
  }
}
