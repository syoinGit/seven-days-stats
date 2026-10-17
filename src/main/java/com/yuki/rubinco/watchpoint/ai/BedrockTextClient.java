package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/** Shared Converse transport. Callers own prompts, generation limits and domain validation. */
@Service
@RequiredArgsConstructor
public class BedrockTextClient {

  private final BedrockRuntimeClient runtime;
  private final AiAnalysisProperties properties;

  public String generate(String systemPrompt, String prompt, int maxTokens, float temperature) {
    var response = runtime.converse(ConverseRequest.builder()
        .modelId(properties.modelId())
        .system(SystemContentBlock.fromText(systemPrompt))
        .messages(Message.builder().role(ConversationRole.USER)
            .content(ContentBlock.fromText(prompt)).build())
        .inferenceConfig(InferenceConfiguration.builder()
            .maxTokens(maxTokens).temperature(temperature).build())
        .build());
    if (response.output() == null || response.output().message() == null) {
      throw new IllegalStateException("Bedrockからメッセージが返されませんでした。");
    }
    return response.output().message().content().stream()
        .map(ContentBlock::text)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.joining())
        .strip();
  }
}
