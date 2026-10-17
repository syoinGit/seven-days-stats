package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import com.yuki.rubinco.watchpoint.readmodel.WatchpointAiObservationService.AnalysisRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Invokes Bedrock Converse and validates its structured WATCHPOINT response. */
@Service
@RequiredArgsConstructor
public class BedrockWatchpointClient {

  private static final String RESPONSE_INSTRUCTION = """
      次の観測リクエストに従って投稿を1件生成してください。
      応答は説明やMarkdownコードフェンスを付けず、次の形のJSONオブジェクトだけにしてください。
      {"body":"60文字以内・改行なしの日本語の短いつぶやき","evidenceKeys":["根拠キー"]}

      evidenceKeys には、観測リクエストに実際に存在する根拠キーだけを入れてください。
      許可される根拠キー一覧はリクエストごとに末尾へ示します。空配列は禁止です。

      観測リクエスト:
      """;

  private final BedrockTextClient textClient;
  private final AiAnalysisProperties properties;
  private final ObjectMapper objectMapper;

  public GeneratedPost generate(AnalysisRequest analysisRequest) {
    String requestJson = serialize(analysisRequest);
    String requestInstruction = RESPONSE_INSTRUCTION
        + "\n今回の許可根拠キー: " + String.join(", ", allowedEvidenceKeys(analysisRequest))
        + "\n\n"
        + requestJson;
    String responseText = textClient.generate(analysisRequest.systemPrompt(),
        requestInstruction, properties.maxOutputTokens(), 0.6f);
    GeneratedPost generated = deserialize(responseText);
    validate(generated, analysisRequest);
    return new GeneratedPost(generated.body().strip(), List.copyOf(generated.evidenceKeys()));
  }

  private String serialize(AnalysisRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception exception) {
      throw new BedrockGenerationException("AI観測データをJSONへ変換できませんでした。", exception);
    }
  }

  private GeneratedPost deserialize(String responseText) {
    if (responseText.isBlank()) {
      throw new BedrockGenerationException("Bedrockから空の応答が返されました。");
    }
    try {
      return objectMapper.readValue(JsonObjectResponse.extract(responseText), GeneratedPost.class);
    } catch (Exception exception) {
      throw new BedrockGenerationException("Bedrockの応答が要求したJSON形式ではありません。", exception);
    }
  }

  private void validate(GeneratedPost generated, AnalysisRequest request) {
    if (generated == null || generated.body() == null || generated.body().isBlank()) {
      throw new BedrockGenerationException("Bedrockの投稿本文が空です。");
    }
    if (generated.body().length() > request.outputContract().maxBodyCharacters()) {
      throw new BedrockGenerationException("Bedrockの投稿本文が文字数上限を超えています。");
    }
    if (generated.body().contains("```") || generated.body().matches("(?s).*^#{1,6}\\s.*")) {
      throw new BedrockGenerationException("Bedrockの投稿本文に許可されていないMarkdownがあります。");
    }
    if (generated.evidenceKeys() == null || generated.evidenceKeys().isEmpty()) {
      throw new BedrockGenerationException("Bedrockの応答に根拠キーがありません。");
    }
    Set<String> allowedKeys = allowedEvidenceKeys(request);
    if (!allowedKeys.containsAll(generated.evidenceKeys())) {
      Set<String> invalidKeys = new HashSet<>(generated.evidenceKeys());
      invalidKeys.removeAll(allowedKeys);
      throw new BedrockGenerationException(
          "Bedrockの応答に存在しない根拠キーが含まれています: " + invalidKeys);
    }
  }

  private Set<String> allowedEvidenceKeys(AnalysisRequest request) {
    Set<String> allowedKeys = new TreeSet<>();
    allowedKeys.addAll(List.of("current-totals", "comparison-totals", "world-context"));
    request.observation().survivors().forEach(item -> allowedKeys.add(item.evidenceKey()));
    request.observation().events().forEach(item -> allowedKeys.add(item.evidenceKey()));
    return allowedKeys;
  }

  public record GeneratedPost(String body, List<String> evidenceKeys) {
  }

  public static class BedrockGenerationException extends RuntimeException {

    public BedrockGenerationException(String message) {
      super(message);
    }

    public BedrockGenerationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
