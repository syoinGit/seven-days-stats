package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared InvokeModel transport (Anthropic Messages body), and the one way the app calls Bedrock: every call is counted
 * and limited by AiCallBudget first (calls a day and an hour, tokens a day, output tokens per call), and a call over
 * the limit is not made. Callers own prompts, generation limits and domain validation.
 * The body works for Haiku 4.5 (the default) and Haiku 5.5 alike: no temperature/top_p/top_k, which 5.5 rejects, and
 * thinking turned off, which 5.5 would otherwise do by default; every caller wants a short piece of text, not reasoning.
 */
@Service
@RequiredArgsConstructor
public class BedrockTextClient {

  static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final BedrockRuntimeClient runtime;
  private final AiAnalysisProperties properties;
  private final AiCallBudget budget;

  public String generate(String systemPrompt, String prompt, int maxTokens) {
    return generate(systemPrompt, prompt, maxTokens, null);
  }

  /**
   * Optional time budget for isolated jobs; existing publishing callers keep their settings. Throws
   * AiCallBudget.ExceededException, without calling Bedrock, when the call would go over a limit.
   */
  public String generate(String systemPrompt, String prompt, int maxTokens, Duration timeout) {
    var now = java.time.ZonedDateTime.now(AiCallBudget.TOKYO);
    budget.reserve(now);
    ObjectNode body = JSON.createObjectNode();
    body.put("anthropic_version", ANTHROPIC_VERSION);
    body.put("max_tokens", budget.cap(maxTokens));
    body.put("system", systemPrompt);
    body.putObject("thinking").put("type", "disabled");
    ObjectNode message = body.putArray("messages").addObject();
    message.put("role", "user");
    message.put("content", prompt);
    var request = InvokeModelRequest.builder()
        .modelId(properties.modelId())
        .contentType("application/json")
        .accept("application/json")
        .body(SdkBytes.fromUtf8String(JSON.writeValueAsString(body)));
    if (timeout != null) request.overrideConfiguration(config -> config
        .apiCallTimeout(timeout).apiCallAttemptTimeout(timeout));
    var response = runtime.invokeModel(request.build());
    if (response.body() == null) throw new IllegalStateException("Bedrockからメッセージが返されませんでした。");
    JsonNode reply = JSON.readTree(response.body().asUtf8String());
    // What the reply reports it used; when it says nothing, count the most it could have produced.
    JsonNode usage = reply.path("usage");
    budget.spent(now, usage.path("input_tokens").asLong(0),
        usage.has("output_tokens") ? usage.path("output_tokens").asLong(0) : budget.cap(maxTokens));
    // A refusal comes back as a normal reply with this stop reason; its text is not something to publish.
    if ("refusal".equals(reply.path("stop_reason").asString(""))) {
      throw new IllegalStateException("Bedrockが生成を断りました（stop_reason=refusal）。");
    }
    StringBuilder text = new StringBuilder();
    for (JsonNode block : reply.path("content")) {
      if ("text".equals(block.path("type").asString(""))) text.append(block.path("text").asString(""));
    }
    if (text.isEmpty()) throw new IllegalStateException("Bedrockからメッセージが返されませんでした。");
    return text.toString().strip();
  }
}
