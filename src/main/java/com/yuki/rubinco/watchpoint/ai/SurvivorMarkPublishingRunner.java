package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SurvivorMarkPublishingRunner {
  private final SurvivorMarkPublishingService publishingService;
  private final ServerActivityGate gate;

  /** Frequent checks never create a second post, and wait for Mark's daily random nighttime slot. */
  @Scheduled(cron = "${app.survivor-mark.schedule-cron:0 */5 20-23 * * *}", zone = "Asia/Tokyo")
  public void publishTrailReport() {
    // AI posts describe a live server; nothing is generated while it is stopped.
    if (!gate.aiAllowed("7dtd")) return;
    try {
      var result = publishingService.publishIfDue();
      if (result.status() == SurvivorMarkPublishingService.PublishStatus.PUBLISHED) {
        log.info("Survivor Mark trail report published. date={}, candidate={}",
            result.date(), result.candidate().key());
      }
    } catch (RuntimeException exception) {
      log.error("Survivor Mark publishing failed; the next nightly check will retry.", exception);
    }
  }
}
