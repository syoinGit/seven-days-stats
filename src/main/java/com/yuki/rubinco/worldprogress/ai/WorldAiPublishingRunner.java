package com.yuki.rubinco.worldprogress.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks often; the service posts once per interval per site and only while the server runs. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldAiPublishingRunner {
  private final WorldAiPublishingService publishingService;

  @Scheduled(initialDelayString = "${app.world-ai.initial-delay:5m}",
      fixedDelayString = "${app.world-ai.check-interval:10m}")
  public void publish() {
    try {
      publishingService.publishDue();
    } catch (RuntimeException exception) {
      log.error("World AI publishing failed.", exception);
    }
  }
}
