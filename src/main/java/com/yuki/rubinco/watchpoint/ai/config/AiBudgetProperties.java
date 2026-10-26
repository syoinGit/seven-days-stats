package com.yuki.rubinco.watchpoint.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hard limits on every Bedrock call, whatever feature makes it (AiCallBudget). Days and hours are Tokyo time. A value
 * left out or not positive takes the default; a value above the ceiling is held at the ceiling, so a typo in the
 * environment can't lift the limit. At the defaults a day can't cost more than about 250k input and 80k output tokens
 * (with Haiku 4.5 at $1 / $5 per million, about $0.65), plus at most one call's worth past the token limits.
 */
@ConfigurationProperties(prefix = "app.ai.budget")
public record AiBudgetProperties(int dailyCalls, int hourlyCalls, long dailyInputTokens, long dailyOutputTokens, int maxTokensPerCall) {
  static final int DAILY_CALLS_CEILING = 200;
  static final int HOURLY_CALLS_CEILING = 60;
  static final long DAILY_INPUT_CEILING = 1_000_000;
  static final long DAILY_OUTPUT_CEILING = 300_000;
  static final int MAX_TOKENS_CEILING = 4000;

  public AiBudgetProperties {
    dailyCalls = dailyCalls <= 0 ? 50 : Math.min(dailyCalls, DAILY_CALLS_CEILING);
    hourlyCalls = hourlyCalls <= 0 ? 20 : Math.min(hourlyCalls, Math.min(HOURLY_CALLS_CEILING, dailyCalls));
    dailyInputTokens = dailyInputTokens <= 0 ? 250_000 : Math.min(dailyInputTokens, DAILY_INPUT_CEILING);
    dailyOutputTokens = dailyOutputTokens <= 0 ? 80_000 : Math.min(dailyOutputTokens, DAILY_OUTPUT_CEILING);
    maxTokensPerCall = maxTokensPerCall <= 0 ? 2000 : Math.min(maxTokensPerCall, MAX_TOKENS_CEILING);
  }
}
