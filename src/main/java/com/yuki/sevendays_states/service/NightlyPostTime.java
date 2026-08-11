package com.yuki.sevendays_states.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.SplittableRandom;

/** Stable per-day posting time within the configured evening window. */
final class NightlyPostTime {
  private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
  private static final int WINDOW_END_HOUR = 23;

  private NightlyPostTime() {
  }

  static OffsetDateTime forDate(LocalDate date, int startHour, long seed) {
    int boundedStart = Math.max(0, Math.min(startHour, WINDOW_END_HOUR - 1));
    int windowMinutes = (WINDOW_END_HOUR - boundedStart) * 60;
    SplittableRandom random = new SplittableRandom(date.toEpochDay() ^ seed);
    return date.atTime(boundedStart, 0).atZone(JAPAN).toOffsetDateTime()
        .plusMinutes(random.nextInt(windowMinutes));
  }
}
