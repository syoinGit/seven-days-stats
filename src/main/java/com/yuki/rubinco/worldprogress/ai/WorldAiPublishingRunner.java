package com.yuki.rubinco.worldprogress.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks often; the AI murmurs once per interval, about one server where someone is connected. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldAiPublishingRunner {
  private final AiMurmurService murmurs;

  @Scheduled(initialDelayString = "${app.world-ai.initial-delay:5m}",
      fixedDelayString = "${app.world-ai.check-interval:10m}")
  public void publish() {
    try {
      murmurs.publishDue();
    } catch (RuntimeException exception) {
      log.error("AI murmur failed.", exception);
    }
  }
}
