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
    return generate(systemPrompt, prompt, maxTokens, temperature, null);
  }

  /** Optional call budget for isolated jobs; existing publishing callers keep their settings. */
  public String generate(String systemPrompt, String prompt, int maxTokens, float temperature,
      java.time.Duration timeout) {
    var request = ConverseRequest.builder()
        .modelId(properties.modelId())
        .system(SystemContentBlock.fromText(systemPrompt))
        .messages(Message.builder().role(ConversationRole.USER)
            .content(ContentBlock.fromText(prompt)).build())
        .inferenceConfig(InferenceConfiguration.builder()
            .maxTokens(maxTokens).temperature(temperature).build())
        ;
    if (timeout != null) request.overrideConfiguration(config -> config
        .apiCallTimeout(timeout).apiCallAttemptTimeout(timeout));
    var response = runtime.converse(request.build());
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
