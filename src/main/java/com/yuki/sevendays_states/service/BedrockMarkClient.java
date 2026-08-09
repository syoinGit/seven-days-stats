package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import tools.jackson.databind.ObjectMapper;

/** One compact, fact-bounded Bedrock call for Mark's daily trail report. */
@Service
@RequiredArgsConstructor
public class BedrockMarkClient {

  private static final String SYSTEM = """
      あなたは経験豊富で口数の少ない男性サバイバー、マークです。入力は2〜5日前の確定済みログを
      集計したものです。今日のプレイヤー位置や名前、現在の行動は一切知りません。
      入力にない戦闘・人物・物資・建物内部を作らず、直接遭遇したような表現もしません。
      「〜らしい」「〜の気配が残っていた」のような慎重な表現だけを使い、短い日本語の探索記録に
      してください。少し皮肉屋だが落ち着いた口調にし、絵文字・ハッシュタグ・映画の台詞は使いません。
      JSON以外は返さず、形式は {"body":"100文字以内","evidenceKeys":["poi"]} です。
      evidenceKeys は入力に実際にある poi, kills, sleepers, zombies のみを入れてください。
      """;

  private final BedrockRuntimeClient bedrockRuntimeClient;
  private final AiAnalysisProperties aiProperties;
  private final ObjectMapper objectMapper;

  public GeneratedMarkPost generate(SurvivorMarkCandidateService.Candidate candidate) {
    String request = serialize(new MarkFacts(candidate.poi(), candidate.kills(), candidate.sleepers(),
        candidate.zombieTypes()));
    String response = bedrockRuntimeClient.converse(ConverseRequest.builder()
            .modelId(aiProperties.modelId())
            .system(SystemContentBlock.fromText(SYSTEM))
            .messages(Message.builder().role(ConversationRole.USER)
                .content(ContentBlock.fromText(request)).build())
            .inferenceConfig(InferenceConfiguration.builder().maxTokens(160).temperature(0.45f).build())
            .build())
        .output().message().content().stream().filter(block -> block.text() != null)
        .map(ContentBlock::text).reduce("", String::concat).strip();
    try {
      GeneratedMarkPost generated = objectMapper.readValue(extractJson(response), GeneratedMarkPost.class);
      validate(generated, candidate);
      return new GeneratedMarkPost(generated.body().strip(), List.copyOf(generated.evidenceKeys()));
    } catch (BedrockMarkException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new BedrockMarkException("BedrockのMark投稿応答を解析できませんでした。", exception);
    }
  }

  private String serialize(MarkFacts facts) {
    try { return objectMapper.writeValueAsString(facts); }
    catch (Exception exception) { throw new BedrockMarkException("Mark観測事実をJSON化できませんでした。", exception); }
  }

  private String extractJson(String response) {
    int start = response.indexOf('{');
    int end = response.lastIndexOf('}');
    if (start < 0 || end <= start) throw new BedrockMarkException("BedrockのMark応答にJSONがありません。");
    return response.substring(start, end + 1);
  }

  private void validate(GeneratedMarkPost generated, SurvivorMarkCandidateService.Candidate candidate) {
    if (generated == null || generated.body() == null || generated.body().isBlank()
        || generated.body().codePointCount(0, generated.body().length()) > TimelinePostService.MAX_POST_CHARACTERS) {
      throw new BedrockMarkException("BedrockのMark本文が不正です。");
    }
    if (generated.evidenceKeys() == null || generated.evidenceKeys().isEmpty()) {
      throw new BedrockMarkException("BedrockのMark応答に根拠キーがありません。");
    }
    Set<String> allowed = new HashSet<>();
    if (!candidate.poi().isBlank()) allowed.add("poi");
    if (candidate.kills() > 0) allowed.add("kills");
    if (candidate.sleepers() > 0) allowed.add("sleepers");
    if (!candidate.zombieTypes().isEmpty()) allowed.add("zombies");
    if (!allowed.containsAll(generated.evidenceKeys())) {
      throw new BedrockMarkException("BedrockのMark応答に存在しない根拠キーがあります。");
    }
  }

  public record MarkFacts(String poi, int kills, int sleepers, List<String> zombies) { }
  public record GeneratedMarkPost(String body, List<String> evidenceKeys) { }
  public static class BedrockMarkException extends RuntimeException {
    public BedrockMarkException(String message) { super(message); }
    public BedrockMarkException(String message, Throwable cause) { super(message, cause); }
  }
}
