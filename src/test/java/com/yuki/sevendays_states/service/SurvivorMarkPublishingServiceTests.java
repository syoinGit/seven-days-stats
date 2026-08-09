package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SurvivorMarkPublishingServiceTests {

  @Test
  void publishesAReportFromTheSelectedHistoricalCandidate() {
    SurvivorMarkCandidateService candidates = mock(SurvivorMarkCandidateService.class);
    TimelinePostService timeline = mock(TimelinePostService.class);
    LocalDate date = LocalDate.of(2026, 8, 10);
    var candidate = new SurvivorMarkCandidateService.Candidate("1:2", 120, 240, 2, 0, 0,
        List.of("zombieNurseRadiated"), 70, "古い本屋");
    when(candidates.select(eq(date), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(candidate));
    when(timeline.publishMark(eq(date), org.mockito.ArgumentMatchers.any(OffsetDateTime.class), anyString(),
        eq("X:150 Z:250"), anyString(), eq("1:2"), eq(""), anyInt())).thenReturn(true);

    SurvivorMarkPublishingService service = new SurvivorMarkPublishingService(
        new SurvivorMarkProperties(true, true, false, 4, 14, 1, 2, 5, 30),
        new SurvivorKarenProperties(false, true, false, 3, 12, "us-east-1", "model", "", "prefix", ""),
        candidates, new MarkPostGenerator(), new MarkImagePromptGenerator(), new MarkPopularityService(),
        mock(ImageGenerationService.class), timeline);

    var result = service.publish(date, OffsetDateTime.of(2026, 8, 10, 14, 0, 0, 0, ZoneOffset.ofHours(9)));

    assertThat(result.status()).isEqualTo(SurvivorMarkPublishingService.PublishStatus.PUBLISHED);
    assertThat(result.candidate()).isEqualTo(candidate);
    assertThat(result.imageAttached()).isFalse();
    verify(timeline).publishMark(eq(date), org.mockito.ArgumentMatchers.any(OffsetDateTime.class), anyString(),
        eq("X:150 Z:250"), anyString(), eq("1:2"), eq(""), anyInt());
  }
}
