package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.entity.AiPostType;
import com.yuki.sevendays_states.web.WatchpointAiObservationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WatchpointAiPublishingServiceTests {

  @Test
  void analysisTypesArePersistedButNeverBroadcastToGame() {
    for (AiPostType type : List.of(
        AiPostType.PLAYER_ANALYSIS, AiPostType.SERVER_ANALYSIS, AiPostType.DAILY_SUMMARY)) {
      WatchpointAiObservationService observations = mock(WatchpointAiObservationService.class);
      BedrockWatchpointClient bedrock = mock(BedrockWatchpointClient.class);
      AiCommentService comments = mock(AiCommentService.class);
      SevenDaysTelnetCommandClient telnet = mock(SevenDaysTelnetCommandClient.class);
      var request = mock(WatchpointAiObservationService.AnalysisRequest.class);
      var saved = new AiCommentService.AiCommentEntry(
          20L, null, "WATCHPOINT観測記録", "分析本文", OffsetDateTime.now(ZoneOffset.UTC),
          "AWS_BEDROCK", null, List.of(), type, null, true);
      when(observations.buildRequest(type, 0)).thenReturn(request);
      when(bedrock.generate(request)).thenReturn(
          new BedrockWatchpointClient.GeneratedPost("分析本文", List.of("current-totals")));
      when(comments.publishGenerated(
          "WATCHPOINT観測記録", "分析本文", "AWS_BEDROCK", type, null)).thenReturn(saved);

      var result = new WatchpointAiPublishingService(
          properties(true), observations, bedrock, comments, telnet).publishNow(type);

      assertThat(result.status()).isEqualTo(WatchpointAiPublishingService.PublishStatus.PUBLISHED);
      verify(comments).publishGenerated(
          "WATCHPOINT観測記録", "分析本文", "AWS_BEDROCK", type, null);
      verify(telnet, never()).broadcast(org.mockito.ArgumentMatchers.any());
    }
  }

  @Test
  void bedrockFailureIsContainedAndCycleIsSkipped() {
    WatchpointAiObservationService observations = mock(WatchpointAiObservationService.class);
    BedrockWatchpointClient bedrock = mock(BedrockWatchpointClient.class);
    AiCommentService comments = mock(AiCommentService.class);
    SevenDaysTelnetCommandClient telnet = mock(SevenDaysTelnetCommandClient.class);
    var request = mock(WatchpointAiObservationService.AnalysisRequest.class);
    when(observations.buildRequest()).thenReturn(request);
    when(bedrock.generate(request)).thenThrow(new RuntimeException("bedrock unavailable"));

    var result = new WatchpointAiPublishingService(
        properties(true), observations, bedrock, comments, telnet).publishNow();

    assertThat(result.status()).isEqualTo(WatchpointAiPublishingService.PublishStatus.FAILED);
    verify(telnet, never()).broadcast(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void generatesThenPersistsValidatedObservation() {
    AiAnalysisProperties properties = properties(true);
    WatchpointAiObservationService observations = mock(WatchpointAiObservationService.class);
    BedrockWatchpointClient bedrock = mock(BedrockWatchpointClient.class);
    AiCommentService comments = mock(AiCommentService.class);
    SevenDaysTelnetCommandClient telnet = mock(SevenDaysTelnetCommandClient.class);
    WatchpointAiObservationService.AnalysisRequest request = mock(
        WatchpointAiObservationService.AnalysisRequest.class);
    AiCommentService.AiCommentEntry saved = new AiCommentService.AiCommentEntry(
        10L, null, "WATCHPOINT観測記録", "観測本文",
        OffsetDateTime.now(ZoneOffset.UTC), "AWS_BEDROCK", null, List.of());
    when(observations.buildRequest()).thenReturn(request);
    when(bedrock.generate(request)).thenReturn(
        new BedrockWatchpointClient.GeneratedPost("観測本文", List.of("current-totals")));
    when(comments.publishGenerated("WATCHPOINT観測記録", "観測本文", "AWS_BEDROCK"))
        .thenReturn(saved);

    WatchpointAiPublishingService.PublishResult result =
        new WatchpointAiPublishingService(properties, observations, bedrock, comments, telnet)
            .publishNow();

    assertThat(result.status()).isEqualTo(WatchpointAiPublishingService.PublishStatus.PUBLISHED);
    assertThat(result.comment()).isEqualTo(saved);
    verify(comments).publishGenerated("WATCHPOINT観測記録", "観測本文", "AWS_BEDROCK");
    var ordered = inOrder(comments, telnet);
    ordered.verify(comments).publishGenerated("WATCHPOINT観測記録", "観測本文", "AWS_BEDROCK");
    ordered.verify(telnet).broadcast("WATCHPOINT: 観測本文");
  }

  @Test
  void doesNothingWhenIntegrationIsDisabled() {
    WatchpointAiObservationService observations = mock(WatchpointAiObservationService.class);
    BedrockWatchpointClient bedrock = mock(BedrockWatchpointClient.class);
    AiCommentService comments = mock(AiCommentService.class);
    SevenDaysTelnetCommandClient telnet = mock(SevenDaysTelnetCommandClient.class);

    WatchpointAiPublishingService.PublishResult result =
        new WatchpointAiPublishingService(properties(false), observations, bedrock, comments, telnet)
            .publishIfDue();

    assertThat(result.status()).isEqualTo(WatchpointAiPublishingService.PublishStatus.DISABLED);
    verify(observations, never()).buildRequest();
    verify(bedrock, never()).generate(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void skipsGenerationWhenRecentlyPublished() {
    WatchpointAiObservationService observations = mock(WatchpointAiObservationService.class);
    BedrockWatchpointClient bedrock = mock(BedrockWatchpointClient.class);
    AiCommentService comments = mock(AiCommentService.class);
    SevenDaysTelnetCommandClient telnet = mock(SevenDaysTelnetCommandClient.class);
    when(comments.latestBySourceType("AWS_BEDROCK")).thenReturn(Optional.of(
        new AiCommentService.AiCommentEntry(10L, null, "WATCHPOINT観測記録", "本文",
            OffsetDateTime.now(ZoneOffset.UTC), "AWS_BEDROCK", null, List.of())));

    WatchpointAiPublishingService.PublishResult result =
        new WatchpointAiPublishingService(properties(true), observations, bedrock, comments, telnet)
            .publishIfDue();

    assertThat(result.status()).isEqualTo(WatchpointAiPublishingService.PublishStatus.NOT_DUE);
    verify(bedrock, never()).generate(org.mockito.ArgumentMatchers.any());
    verify(telnet, never()).broadcast(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void skipsPaidGenerationWhenObservationWindowHasNoActivity() {
    WatchpointAiObservationService observations = mock(WatchpointAiObservationService.class);
    BedrockWatchpointClient bedrock = mock(BedrockWatchpointClient.class);
    AiCommentService comments = mock(AiCommentService.class);
    SevenDaysTelnetCommandClient telnet = mock(SevenDaysTelnetCommandClient.class);
    var request = mock(WatchpointAiObservationService.AnalysisRequest.class);
    var observation = mock(WatchpointAiObservationService.Observation.class);
    when(observations.buildRequest()).thenReturn(request);
    when(request.observation()).thenReturn(observation);
    when(observation.currentTotals()).thenReturn(new WatchpointAiObservationService.ActivityTotals(
        0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO));
    when(observation.events()).thenReturn(List.of());

    var result = new WatchpointAiPublishingService(
        properties(true), observations, bedrock, comments, telnet).publishIfDue();

    assertThat(result.status()).isEqualTo(WatchpointAiPublishingService.PublishStatus.NO_ACTIVITY);
    verify(bedrock, never()).generate(org.mockito.ArgumentMatchers.any());
  }

  private AiAnalysisProperties properties(boolean enabled) {
    return new AiAnalysisProperties(
        30, 60, "classpath:prompts/watchpoint-system-prompt.txt", enabled,
        "ap-northeast-1", "jp.anthropic.claude-haiku-4-5-20251001-v1:0", 400, 30, 60000);
  }
}
