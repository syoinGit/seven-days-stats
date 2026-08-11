package com.yuki.sevendays_states.batch;

import com.yuki.sevendays_states.service.WatchpointAiPublishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WatchpointAiPublishingRunner {

  private final WatchpointAiPublishingService publishingService;

  @Scheduled(
      initialDelayString = "${app.ai.initial-delay:60s}",
      fixedDelayString = "${app.ai.schedule-interval:30m}")
  public void publishObservation() {
    try {
      WatchpointAiPublishingService.PublishResult result = publishingService.publishIfDue();
      if (result.status() == WatchpointAiPublishingService.PublishStatus.PUBLISHED) {
        log.info("WATCHPOINT Bedrock observation published. commentId={}", result.comment().id());
      }
    } catch (RuntimeException exception) {
      log.error("WATCHPOINT Bedrock observation generation failed.", exception);
    }
  }
}
