package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.web.WatchpointAiObservationService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BedrockWatchpointClientTests {

  @Mock
  private BedrockRuntimeClient runtimeClient;

  private BedrockWatchpointClient client;

  @BeforeEach
  void setUp() {
    AiAnalysisProperties properties = new AiAnalysisProperties(true,
        30, 60, "classpath:prompts/watchpoint-system-prompt.txt",
        "ap-northeast-1", "jp.anthropic.claude-haiku-4-5-20251001-v1:0", 400,
        java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1), 10);
    client = new BedrockWatchpointClient(runtimeClient, properties, new ObjectMapper());
  }

  @Test
  void invokesConverseAndAcceptsGroundedJson() {
    when(runtimeClient.converse(any(ConverseRequest.class))).thenReturn(response(
        "{\"body\":\"探索範囲が少し広がりました。\",\"evidenceKeys\":[\"event-001\"]}"));

    BedrockWatchpointClient.GeneratedPost generated = client.generate(request());

    assertThat(generated.body()).isEqualTo("探索範囲が少し広がりました。");
    assertThat(generated.evidenceKeys()).containsExactly("event-001");
    ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
    verify(runtimeClient).converse(captor.capture());
    assertThat(captor.getValue().modelId())
        .isEqualTo("jp.anthropic.claude-haiku-4-5-20251001-v1:0");
    assertThat(captor.getValue().system().getFirst().text()).isEqualTo("WATCHPOINT system");
    assertThat(captor.getValue().messages().getFirst().role()).isEqualTo(ConversationRole.USER);
  }

  @Test
  void rejectsUnknownEvidenceKey() {
    when(runtimeClient.converse(any(ConverseRequest.class))).thenReturn(response(
        "{\"body\":\"存在しない観測です。\",\"evidenceKeys\":[\"event-999\"]}"));

    assertThatThrownBy(() -> client.generate(request()))
        .isInstanceOf(BedrockWatchpointClient.BedrockGenerationException.class)
        .hasMessageContaining("存在しない根拠キー");
  }

  @Test
  void acceptsJsonWrappedInMarkdownCodeFence() {
    when(runtimeClient.converse(any(ConverseRequest.class))).thenReturn(response("""
        ```json
        {"body":"生存者の活動を観測しました。","evidenceKeys":["current-totals"]}
        ```
        """));

    BedrockWatchpointClient.GeneratedPost generated = client.generate(request());

    assertThat(generated.body()).isEqualTo("生存者の活動を観測しました。");
    assertThat(generated.evidenceKeys()).containsExactly("current-totals");
  }

  @Test
  void acceptsIntroductoryTextAndBracesInsideJsonString() {
    when(runtimeClient.converse(any(ConverseRequest.class))).thenReturn(response(
        "生成結果です。\n{\"body\":\"観測範囲は {静か} でした。\",\"evidenceKeys\":[\"world-context\"]}"));

    BedrockWatchpointClient.GeneratedPost generated = client.generate(request());

    assertThat(generated.body()).isEqualTo("観測範囲は {静か} でした。");
  }

  @Test
  void sendsExplicitJsonOnlyInstruction() {
    when(runtimeClient.converse(any(ConverseRequest.class))).thenReturn(response(
        "{\"body\":\"観測しました。\",\"evidenceKeys\":[\"current-totals\"]}"));

    client.generate(request());

    ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
    verify(runtimeClient).converse(captor.capture());
    String userMessage = captor.getValue().messages().getFirst().content().getFirst().text();
    assertThat(userMessage).contains("JSONオブジェクトだけ");
    assertThat(userMessage).contains("100文字以内");
    assertThat(userMessage).contains("\"body\"");
  }

  private ConverseResponse response(String text) {
    return ConverseResponse.builder()
        .output(ConverseOutput.fromMessage(Message.builder()
            .role(ConversationRole.ASSISTANT)
            .content(ContentBlock.fromText(text))
            .build()))
        .build();
  }

  private WatchpointAiObservationService.AnalysisRequest request() {
    OffsetDateTime now = OffsetDateTime.of(2026, 8, 6, 0, 0, 0, 0, ZoneOffset.UTC);
    WatchpointAiObservationService.ObservationWindow window =
        new WatchpointAiObservationService.ObservationWindow(now.minusMinutes(30), now, 30);
    WatchpointAiObservationService.ActivityTotals totals =
        new WatchpointAiObservationService.ActivityTotals(
            0, 0, 1, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    WatchpointAiObservationService.ObservedEvent event =
        new WatchpointAiObservationService.ObservedEvent(
            "event-001", now.minusMinutes(2), "KILL", "生存者A", "生存者Aが敵を討伐した");
    WatchpointAiObservationService.Observation observation =
        new WatchpointAiObservationService.Observation(
            window, window,
            new WatchpointAiObservationService.WorldContext(
                1, "12:00", null, BigDecimal.valueOf(50), 1, 10),
            totals, totals, List.of(), List.of(), List.of(event),
            new WatchpointAiObservationService.DataPolicy(List.of("raw log lines"), "test"));
    return new WatchpointAiObservationService.AnalysisRequest(
        "watchpoint.observation.v1", now, "AWS_BEDROCK_CONVERSE", "WATCHPOINT system",
        "短文を生成", new WatchpointAiObservationService.OutputContract(
        "application/json", 100, false, List.of("body", "evidenceKeys"), "test"), observation);
  }
}
