package com.yuki.rubinco.worldprogress.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI posts for the world statistics sites (Octarine, Luminite). Uses the app.ai Bedrock model. */
@ConfigurationProperties(prefix = "app.world-ai")
public record WorldAiProperties(boolean enabled, Duration interval, int maxOutputTokens) {
  public WorldAiProperties {
    interval = interval == null || interval.compareTo(Duration.ofMinutes(30)) < 0
        ? Duration.ofHours(3) : interval;
    maxOutputTokens = maxOutputTokens <= 0 ? 300 : Math.min(maxOutputTokens, 800);
  }
}
