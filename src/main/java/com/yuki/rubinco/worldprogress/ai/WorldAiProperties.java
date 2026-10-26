package com.yuki.rubinco.worldprogress.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The AI's murmur. interval: one murmur per interval across every game (AiMurmurService). enabled and maxOutputTokens
 * are for the world statistics sites (Octarine / Core Keeper only). Uses the app.ai Bedrock model.
 */
@ConfigurationProperties(prefix = "app.world-ai")
public record WorldAiProperties(boolean enabled, Duration interval, int maxOutputTokens) {
  public WorldAiProperties {
    interval = interval == null || interval.compareTo(Duration.ofMinutes(30)) < 0
        ? Duration.ofHours(1) : interval;
    maxOutputTokens = maxOutputTokens <= 0 ? 300 : Math.min(maxOutputTokens, 800);
  }
}
