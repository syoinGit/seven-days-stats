package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WatchpointAiPublishingRunner {

  private final WatchpointAiPublishingService publishingService;
  private final ServerActivityGate gate;

  @Scheduled(
      initialDelayString = "${app.ai.initial-delay:60s}",
      fixedDelayString = "${app.ai.schedule-interval:30m}")
  public void publishObservation() {
    // AI posts describe a live server; nothing is generated while it is stopped.
    if (!gate.aiAllowed("7dtd")) return;
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
