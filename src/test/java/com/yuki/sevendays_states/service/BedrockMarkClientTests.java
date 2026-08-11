package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import tools.jackson.databind.ObjectMapper;

class BedrockMarkClientTests {

  @Test
  void acceptsOnlyEvidenceProvidedByTheHistoricalCandidate() {
    BedrockRuntimeClient runtime = org.mockito.Mockito.mock(BedrockRuntimeClient.class);
    when(runtime.converse(any(ConverseRequest.class))).thenReturn(response(
        "{\"body\":\"古い本屋を覗いた。奥は静かじゃなかったらしい。\",\"evidenceKeys\":[\"poi\",\"kills\"]}"));

    var generated = client(runtime).generate(candidate());

    assertThat(generated.body()).contains("古い本屋");
    assertThat(generated.evidenceKeys()).containsExactly("poi", "kills");
  }

  @Test
  void rejectsEvidenceNotPresentInTheCandidate() {
    BedrockRuntimeClient runtime = org.mockito.Mockito.mock(BedrockRuntimeClient.class);
    when(runtime.converse(any(ConverseRequest.class))).thenReturn(response(
        "{\"body\":\"記録した。\",\"evidenceKeys\":[\"sleepers\"]}"));

    assertThatThrownBy(() -> client(runtime).generate(candidate()))
        .isInstanceOf(BedrockMarkClient.BedrockMarkException.class)
        .hasMessageContaining("存在しない根拠キー");
  }

  private BedrockMarkClient client(BedrockRuntimeClient runtime) {
    return new BedrockMarkClient(runtime, new AiAnalysisProperties(true,
        30, 60, "", "ap-northeast-1", "claude-test", 240,
        java.time.Duration.ofMinutes(30), java.time.Duration.ZERO, 10), new ObjectMapper());
  }

  private SurvivorMarkCandidateService.Candidate candidate() {
    return new SurvivorMarkCandidateService.Candidate("1:2", 120, 240, 2, 0, 0,
        List.of("zombieNurseRadiated"), 70, "古い本屋");
  }

  private ConverseResponse response(String text) {
    return ConverseResponse.builder().output(ConverseOutput.fromMessage(Message.builder()
        .role(ConversationRole.ASSISTANT).content(ContentBlock.fromText(text)).build())).build();
  }
}
