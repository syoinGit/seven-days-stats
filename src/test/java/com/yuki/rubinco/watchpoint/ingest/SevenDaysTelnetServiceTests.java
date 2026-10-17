package com.yuki.rubinco.watchpoint.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SevenDaysTelnetServiceTests {

  @Test
  void parsesWorldTimeCommandResponse() {
    LocalDateTime observedAt = LocalDateTime.of(2026, 8, 2, 12, 30);

    SevenDaysTelnetService.WorldTime time = SevenDaysTelnetService
        .parseWorldTime("Day 42, 04:07", observedAt)
        .orElseThrow();

    assertThat(time.day()).isEqualTo(42);
    assertThat(time.hour()).isEqualTo(4);
    assertThat(time.minute()).isEqualTo(7);
  }

  @Test
  void ignoresCommandEchoAndInvalidWorldTime() {
    LocalDateTime observedAt = LocalDateTime.of(2026, 8, 2, 12, 30);

    assertThat(SevenDaysTelnetService.parseWorldTime("gettime", observedAt)).isEmpty();
    assertThat(SevenDaysTelnetService.parseWorldTime("Day 8, 25:00", observedAt)).isEmpty();
  }
}
