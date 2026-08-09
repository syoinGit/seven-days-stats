package com.yuki.sevendays_states.batch;

import com.yuki.sevendays_states.service.SurvivorMarkPublishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SurvivorMarkPublishingRunner {
  private final SurvivorMarkPublishingService publishingService;

  /** A single scheduled attempt keeps the Bedrock text budget to one normal call per day. */
  @Scheduled(cron = "${app.survivor-mark.schedule-cron:0 23 14 * * *}", zone = "Asia/Tokyo")
  public void publishTrailReport() {
    try {
      var result = publishingService.publishIfDue();
      if (result.status() == SurvivorMarkPublishingService.PublishStatus.PUBLISHED) {
        log.info("Survivor Mark trail report published. date={}, candidate={}",
            result.date(), result.candidate().key());
      }
    } catch (RuntimeException exception) {
      log.error("Survivor Mark publishing failed; it will retry at tomorrow's scheduled window.", exception);
    }
  }
}
