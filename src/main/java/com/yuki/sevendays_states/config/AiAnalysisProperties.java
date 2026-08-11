package com.yuki.sevendays_states.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiAnalysisProperties(
    boolean enabled,
    int windowMinutes,
    int maxEvents,
    String systemPromptResource,
    String awsRegion,
    String modelId,
    int maxOutputTokens,
    Duration scheduleInterval,
    Duration initialDelay,
    int maxPostsPerDay
) {

  public AiAnalysisProperties {
    windowMinutes = windowMinutes <= 0 ? 30 : Math.min(windowMinutes, 180);
    maxEvents = maxEvents <= 0 ? 60 : Math.min(maxEvents, 200);
    systemPromptResource = systemPromptResource == null || systemPromptResource.isBlank()
        ? "classpath:prompts/watchpoint-system-prompt.txt"
        : systemPromptResource;
    awsRegion = awsRegion == null || awsRegion.isBlank() ? "ap-northeast-1" : awsRegion;
    modelId = modelId == null || modelId.isBlank()
        ? "jp.anthropic.claude-haiku-4-5-20251001-v1:0"
        : modelId;
    maxOutputTokens = maxOutputTokens <= 0 ? 400 : Math.min(maxOutputTokens, 1000);
    scheduleInterval = positiveOrDefault(scheduleInterval, Duration.ofMinutes(30));
    if (scheduleInterval.compareTo(Duration.ofMinutes(5)) < 0) {
      scheduleInterval = Duration.ofMinutes(5);
    }
    initialDelay = initialDelay == null || initialDelay.isNegative()
        ? Duration.ofMinutes(1) : initialDelay;
    maxPostsPerDay = maxPostsPerDay <= 0 ? 10 : maxPostsPerDay;
  }

  private static Duration positiveOrDefault(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
