package com.yuki.rubinco.watchpoint.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import com.yuki.rubinco.watchpoint.readmodel.WatchpointAiObservationService;
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
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.JsonNode;
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
        "ap-northeast-1", "anthropic.claude-haiku-5-5", 400,
        java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1), 10);
    client = new BedrockWatchpointClient(new BedrockTextClient(runtimeClient, properties), properties, new ObjectMapper());
  }

  @Test
  void invokesTheModelAndAcceptsGroundedJson() {
    when(runtimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response(
        "{\"body\":\"探索範囲が少し広がりました。\",\"evidenceKeys\":[\"event-001\"]}"));

    BedrockWatchpointClient.GeneratedPost generated = client.generate(request());

    assertThat(generated.body()).isEqualTo("探索範囲が少し広がりました。");
    assertThat(generated.evidenceKeys()).containsExactly("event-001");
    ArgumentCaptor<InvokeModelRequest> captor = ArgumentCaptor.forClass(InvokeModelRequest.class);
    verify(runtimeClient).invokeModel(captor.capture());
    assertThat(captor.getValue().modelId()).isEqualTo("anthropic.claude-haiku-5-5");
    JsonNode body = sent(captor.getValue());
    assertThat(body.path("anthropic_version").asString()).isEqualTo("bedrock-2023-05-31");
    assertThat(body.path("system").asString()).isEqualTo("WATCHPOINT system");
    assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(400);
    // Haiku 5.5 rejects sampling parameters; thinking is off for short text.
    assertThat(body.has("temperature")).isFalse();
    assertThat(body.has("top_p")).isFalse();
    assertThat(body.path("thinking").path("type").asString()).isEqualTo("disabled");
  }

  @Test
  void aRefusalIsNotPublished() {
    when(runtimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(InvokeModelResponse.builder()
        .body(SdkBytes.fromUtf8String("{\"stop_reason\":\"refusal\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}")).build());

    assertThatThrownBy(() -> client.generate(request())).hasMessageContaining("refusal");
  }

  @Test
  void rejectsUnknownEvidenceKey() {
    when(runtimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response(
        "{\"body\":\"存在しない観測です。\",\"evidenceKeys\":[\"event-999\"]}"));

    assertThatThrownBy(() -> client.generate(request()))
        .isInstanceOf(BedrockWatchpointClient.BedrockGenerationException.class)
        .hasMessageContaining("存在しない根拠キー");
  }

  @Test
  void acceptsJsonWrappedInMarkdownCodeFence() {
    when(runtimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response("""
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
    when(runtimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response(
        "生成結果です。\n{\"body\":\"観測範囲は {静か} でした。\",\"evidenceKeys\":[\"world-context\"]}"));

    BedrockWatchpointClient.GeneratedPost generated = client.generate(request());

    assertThat(generated.body()).isEqualTo("観測範囲は {静か} でした。");
  }

  @Test
  void sendsExplicitJsonOnlyInstruction() {
    when(runtimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response(
        "{\"body\":\"観測しました。\",\"evidenceKeys\":[\"current-totals\"]}"));

    client.generate(request());

    ArgumentCaptor<InvokeModelRequest> captor = ArgumentCaptor.forClass(InvokeModelRequest.class);
    verify(runtimeClient).invokeModel(captor.capture());
    String userMessage = sent(captor.getValue()).path("messages").get(0).path("content").asString();
    assertThat(userMessage).contains("JSONオブジェクトだけ");
    assertThat(userMessage).contains("80文字以内");
    assertThat(userMessage).contains("\"body\"");
  }

  private InvokeModelResponse response(String text) {
    var reply = new ObjectMapper().createObjectNode();
    reply.put("stop_reason", "end_turn");
    var block = reply.putArray("content").addObject();
    block.put("type", "text");
    block.put("text", text);
    return InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(reply.toString())).build();
  }

  private static JsonNode sent(InvokeModelRequest request) {
    return new ObjectMapper().readTree(request.body().asUtf8String());
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
