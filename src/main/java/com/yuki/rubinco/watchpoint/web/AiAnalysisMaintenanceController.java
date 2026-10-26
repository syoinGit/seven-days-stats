package com.yuki.rubinco.watchpoint.web;

import com.yuki.rubinco.watchpoint.ai.AiCommentService;
import com.yuki.rubinco.watchpoint.ai.WatchpointAiPublishingService;
import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import com.yuki.rubinco.watchpoint.readmodel.WatchpointAiObservationService;
import com.yuki.rubinco.worldprogress.ai.WorldAiProperties;
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
  private final ObjectMapper objectMapper;
  private final WorldAiProperties murmur;

  @GetMapping("/maintenance/ai-analysis/test")
  public String testPage(Model model) {
    model.addAttribute("bedrockEnabled", properties.enabled());
    model.addAttribute("awsRegion", properties.awsRegion());
    model.addAttribute("modelId", properties.modelId());
    model.addAttribute("windowMinutes", properties.windowMinutes());
    // The AI murmurs once per this interval across every game (AiMurmurService), not on its own schedule.
    model.addAttribute("scheduleMinutes", murmur.interval().toMinutes());
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
            "error", "AI生成が無効です。WATCHPOINT_AI_ENABLEDを確認してください。");
      } else if (result.status() == WatchpointAiPublishingService.PublishStatus.SERVER_STOPPED) {
        redirectAttributes.addFlashAttribute(
            "error", "7DTDサーバーが起動していないため、AIは投稿しません。");
      } else if (result.status() != WatchpointAiPublishingService.PublishStatus.PUBLISHED) {
        redirectAttributes.addFlashAttribute(
            "error", "WATCHPOINTの生成に失敗しました。サーバーログを確認してください。");
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

}
