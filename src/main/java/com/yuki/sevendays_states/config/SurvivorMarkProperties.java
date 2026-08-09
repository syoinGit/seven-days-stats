package com.yuki.sevendays_states.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Controls the low-frequency trail reports produced from already imported historical logs. */
@ConfigurationProperties(prefix = "app.survivor-mark")
public record SurvivorMarkProperties(
    boolean enabled,
    boolean postEnabled,
    boolean bedrockEnabled,
    int postHour,
    int sourceMinAgeDays,
    int sourceMaxAgeDays,
    int locationCooldownDays
) {

  public SurvivorMarkProperties {
    postHour = bounded(postHour, 14, 0, 23);
    sourceMinAgeDays = bounded(sourceMinAgeDays, 2, 1, 14);
    sourceMaxAgeDays = Math.max(sourceMinAgeDays, bounded(sourceMaxAgeDays, 5, 1, 21));
    locationCooldownDays = bounded(locationCooldownDays, 30, 7, 180);
  }

  private static int bounded(int value, int fallback, int min, int max) {
    return value <= 0 ? fallback : Math.max(min, Math.min(value, max));
  }
}
