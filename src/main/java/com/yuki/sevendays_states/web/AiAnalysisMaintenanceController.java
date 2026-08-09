package com.yuki.sevendays_states.web;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import com.yuki.sevendays_states.service.AiCommentService;
import com.yuki.sevendays_states.service.SurvivorKarenPublishingService;
import com.yuki.sevendays_states.service.SurvivorMarkPublishingService;
import com.yuki.sevendays_states.service.WatchpointAiPublishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AiAnalysisMaintenanceController {

  private final AiAnalysisProperties properties;
  private final WatchpointAiObservationService observationService;
  private final WatchpointAiPublishingService publishingService;
  private final AiCommentService aiCommentService;
  private final SurvivorKarenProperties karenProperties;
  private final SurvivorKarenPublishingService karenPublishingService;
  private final SurvivorMarkProperties markProperties;
  private final SurvivorMarkPublishingService markPublishingService;
  private final ObjectMapper objectMapper;

  @GetMapping("/maintenance/ai-analysis/test")
  public String testPage(Model model) {
    model.addAttribute("bedrockEnabled", properties.bedrockEnabled());
    model.addAttribute("awsRegion", properties.awsRegion());
    model.addAttribute("modelId", properties.modelId());
    model.addAttribute("windowMinutes", properties.windowMinutes());
    model.addAttribute("scheduleMinutes", properties.scheduleMinutes());
    model.addAttribute(
        "karenEnabled", karenProperties.enabled() && karenProperties.postEnabled());
    model.addAttribute("karenImageEnabled", karenProperties.imageConfigured());
    model.addAttribute("karenAwsRegion", karenProperties.awsRegion());
    model.addAttribute("karenModelId", karenProperties.imageModelId());
    model.addAttribute("markEnabled", markProperties.enabled() && markProperties.postEnabled()
        && markProperties.bedrockEnabled());
    model.addAttribute("latestComment",
        aiCommentService.latestBySourceType(WatchpointAiPublishingService.SOURCE_TYPE).orElse(null));
    try {
      model.addAttribute("payloadJson", objectMapper.writerWithDefaultPrettyPrinter()
          .writeValueAsString(observationService.buildRequest()));
    } catch (Exception exception) {
      log.warn("WATCHPOINT AI test payload preview could not be generated.", exception);
      model.addAttribute("payloadJson", "観測JSONを生成できませんでした。サーバーログを確認してください。");
    }
    return "ai-analysis-test";
  }

  @PostMapping("/maintenance/ai-analysis/test")
  public String generate(RedirectAttributes redirectAttributes) {
    try {
      WatchpointAiPublishingService.PublishResult result = publishingService.publishNow();
      if (result.status() == WatchpointAiPublishingService.PublishStatus.DISABLED) {
        redirectAttributes.addFlashAttribute(
            "error", "Bedrock連携が無効です。EC2の環境変数を確認してください。");
      } else {
        redirectAttributes.addFlashAttribute("notice", "WATCHPOINTが新しい観測を投稿しました。");
      }
    } catch (RuntimeException exception) {
      log.error("Manual WATCHPOINT Bedrock test failed.", exception);
      redirectAttributes.addFlashAttribute(
          "error", "Bedrockでの生成に失敗しました。IAM・モデル利用許可・サーバーログを確認してください。");
    }
    return "redirect:/maintenance/ai-analysis/test";
  }

  @PostMapping("/maintenance/ai-analysis/test/karen")
  public String generateKaren(RedirectAttributes redirectAttributes) {
    try {
      SurvivorKarenPublishingService.PublishResult result =
          karenPublishingService.publishTodayIfMissing();
      switch (result.status()) {
        case PUBLISHED -> redirectAttributes.addFlashAttribute(
            "notice", result.imageAttached()
                ? "Karenが画像付きの新しい投稿を公開しました。"
                : "Karenが新しい投稿を公開しました。");
        case ALREADY_PUBLISHED -> redirectAttributes.addFlashAttribute(
            "notice", "Karenは今日の投稿を既に公開済みです。");
        case DISABLED -> redirectAttributes.addFlashAttribute(
            "error", "Karen投稿が無効です。EC2の環境変数を確認してください。");
        case TOO_EARLY -> redirectAttributes.addFlashAttribute(
            "error", "Karenの投稿時刻前です。");
      }
    } catch (RuntimeException exception) {
      log.error("Manual Survivor Karen publishing failed.", exception);
      redirectAttributes.addFlashAttribute(
          "error", "Karenの投稿に失敗しました。設定・権限・サーバーログを確認してください。");
    }
    return "redirect:/maintenance/ai-analysis/test";
  }

  @PostMapping("/maintenance/ai-analysis/test/mark")
  public String generateMark(RedirectAttributes redirectAttributes) {
    try {
      SurvivorMarkPublishingService.PublishResult result = markPublishingService.publishTodayIfPossible();
      switch (result.status()) {
        case PUBLISHED -> redirectAttributes.addFlashAttribute(
            "notice", "サバイバーマークが探索記録を公開しました。");
        case NO_CANDIDATE -> redirectAttributes.addFlashAttribute(
            "error", "2〜5日前のログに、投稿できる探索候補がありません。");
        case ALREADY_PUBLISHED -> redirectAttributes.addFlashAttribute(
            "notice", "サバイバーマークはこの候補を既に投稿済みです。");
        case DISABLED -> redirectAttributes.addFlashAttribute(
            "error", "サバイバーマーク投稿が無効です。環境変数を確認してください。");
        case TOO_EARLY, NOT_DUE -> redirectAttributes.addFlashAttribute(
            "notice", "手動投稿では通常発生しない待機状態です。");
        case FAILED -> redirectAttributes.addFlashAttribute(
            "error", "Bedrockでの短文生成に失敗しました。IAM・モデル利用許可・サーバーログを確認してください。");
      }
    } catch (RuntimeException exception) {
      log.error("Manual Survivor Mark publishing failed.", exception);
      redirectAttributes.addFlashAttribute("error", "サバイバーマーク投稿に失敗しました。サーバーログを確認してください。");
    }
    return "redirect:/maintenance/ai-analysis/test";
  }
}
