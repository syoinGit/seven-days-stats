package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import com.yuki.sevendays_states.config.AiAnalysisProperties;
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
    BedrockMarkClient bedrock = mock(BedrockMarkClient.class);
    TimelinePostService timeline = mock(TimelinePostService.class);
    LocalDate date = LocalDate.of(2026, 8, 10);
    var candidate = new SurvivorMarkCandidateService.Candidate("1:2", 120, 240, 2, 0, 0,
        List.of("zombieNurseRadiated"), 70, "古い本屋");
    when(candidates.select(eq(date), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(candidate));
    when(timeline.existsBySourceHash("SURVIVOR_MARK:" + date + ":1:2")).thenReturn(false);
    when(bedrock.generate(candidate)).thenReturn(new BedrockMarkClient.GeneratedMarkPost(
        "古い本屋を覗いた。放射能まみれのナースの気配が残っていた。", List.of("poi", "zombies")));
    when(timeline.publishMark(eq(date), org.mockito.ArgumentMatchers.any(OffsetDateTime.class), anyString(),
        eq("X:150 Z:250"), anyString(), eq("1:2"), eq(""), anyInt())).thenReturn(true);

    SurvivorMarkPublishingService service = new SurvivorMarkPublishingService(
        new AiAnalysisProperties(true, 30, 60, "", "ap-northeast-1", "model", 240,
            java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1), 10),
        new SurvivorMarkProperties(14, 2, 5, 30),
        candidates, bedrock, new MarkPopularityService(), timeline);

    var result = service.publish(date, OffsetDateTime.of(2026, 8, 10, 14, 0, 0, 0, ZoneOffset.ofHours(9)));

    assertThat(result.status()).isEqualTo(SurvivorMarkPublishingService.PublishStatus.PUBLISHED);
    assertThat(result.candidate()).isEqualTo(candidate);
    verify(timeline).publishMark(eq(date), org.mockito.ArgumentMatchers.any(OffsetDateTime.class), anyString(),
        eq("X:150 Z:250"), anyString(), eq("1:2"), eq(""), anyInt());
  }

  @Test
  void masterFlagDisablesManualMarkPublishing() {
    SurvivorMarkCandidateService candidates = mock(SurvivorMarkCandidateService.class);
    BedrockMarkClient bedrock = mock(BedrockMarkClient.class);
    SurvivorMarkPublishingService service = new SurvivorMarkPublishingService(
        new AiAnalysisProperties(false, 30, 60, "", "ap-northeast-1", "model", 240,
            java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1), 10),
        new SurvivorMarkProperties(20, 2, 5, 30), candidates, bedrock,
        new MarkPopularityService(), mock(TimelinePostService.class));

    var result = service.publishTodayIfPossible();

    assertThat(result.status()).isEqualTo(SurvivorMarkPublishingService.PublishStatus.DISABLED);
    verify(candidates, never()).select(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(bedrock, never()).generate(org.mockito.ArgumentMatchers.any());
  }
}
