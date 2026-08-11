package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

class BedrockDiaryClientTests {

  @Test
  void acceptsStructuredDiaryWrappedInCodeFence() {
    BedrockRuntimeClient runtime = mock(BedrockRuntimeClient.class);
    when(runtime.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder()
        .output(ConverseOutput.fromMessage(Message.builder()
            .role(ConversationRole.ASSISTANT)
            .content(ContentBlock.fromText("""
                ```json
                {"title":"病院の灯り","summary":"病院を探索した一日。","tags":["探索","病院"],"body":"生存者たちは病院へ向かった。"}
                ```
                """))
            .build()))
        .build());
    AiAnalysisProperties properties = new AiAnalysisProperties(true,
        30, 60, "classpath:prompts/watchpoint-system-prompt.txt",
        "ap-northeast-1", "model", 400, java.time.Duration.ofMinutes(30),
        java.time.Duration.ofMinutes(1), 10);
    AiAgentProfileService profiles = mock(AiAgentProfileService.class);
    WatchpointAiStateService state = mock(WatchpointAiStateService.class);
    when(profiles.personalityPrompt(AiAgentProfileService.WATCHPOINT)).thenReturn("WATCHPOINT人格");
    when(state.promptContext()).thenReturn("現在状態");
    BedrockDiaryClient client = new BedrockDiaryClient(
        runtime, properties, new ObjectMapper(), profiles, state);

    var diary = client.generate("観測データ", List.of("遠征"));

    assertThat(diary.title()).isEqualTo("病院の灯り");
    assertThat(diary.summary()).isEqualTo("病院を探索した一日。");
    assertThat(diary.tags()).containsExactly("探索", "病院");
    assertThat(diary.body()).isEqualTo("生存者たちは病院へ向かった。");
  }
}
