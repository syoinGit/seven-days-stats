package com.yuki.sevendays_states.web;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import com.yuki.sevendays_states.service.SurvivorMarkCandidateService;
import com.yuki.sevendays_states.service.SurvivorMarkPublishingService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.ObjectMapper;

/** Admin preview for the small, evidence-bounded Survivor Mark Bedrock post. */
@Controller
@RequiredArgsConstructor
@Slf4j
public class SurvivorMarkMaintenanceController {

  private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

  private final SurvivorMarkProperties properties;
  private final AiAnalysisProperties aiProperties;
  private final SurvivorMarkCandidateService candidateService;
  private final SurvivorMarkPublishingService publishingService;
  private final ObjectMapper objectMapper;

  @GetMapping("/maintenance/survivor-mark/test")
  public String testPage(Model model) {
    LocalDate today = LocalDate.now(JAPAN);
    var candidate = candidateService.select(today, properties);
    model.addAttribute("markEnabled", aiProperties.enabled());
    model.addAttribute("modelId", aiProperties.modelId());
    model.addAttribute("sourceMinAgeDays", properties.sourceMinAgeDays());
    model.addAttribute("sourceMaxAgeDays", properties.sourceMaxAgeDays());
    model.addAttribute("candidate", candidate.orElse(null));
    model.addAttribute("candidateJson", json(candidate.orElse(null)));
    return "survivor-mark-test";
  }

  @PostMapping("/maintenance/survivor-mark/test")
  public String publish(RedirectAttributes redirectAttributes) {
    try {
      SurvivorMarkPublishingService.PublishResult result = publishingService.publishTodayIfPossible();
      switch (result.status()) {
        case PUBLISHED -> redirectAttributes.addFlashAttribute("notice", "サバイバーマークが探索記録を公開しました。");
        case NO_CANDIDATE -> redirectAttributes.addFlashAttribute(
            "error", "2〜5日前のログに、投稿できる探索候補がありません。");
        case ALREADY_PUBLISHED -> redirectAttributes.addFlashAttribute(
            "notice", "サバイバーマークはこの候補を既に投稿済みです。");
        case DISABLED -> redirectAttributes.addFlashAttribute(
            "error", "AI投稿が無効です。WATCHPOINT_AI_ENABLEDを確認してください。");
        case TOO_EARLY, NOT_DUE -> redirectAttributes.addFlashAttribute(
            "notice", "手動投稿では通常発生しない待機状態です。");
        case FAILED -> redirectAttributes.addFlashAttribute(
            "error", "Bedrockでの短文生成に失敗しました。IAM・モデル利用許可・サーバーログを確認してください。");
      }
    } catch (RuntimeException exception) {
      log.error("Manual Survivor Mark publishing failed.", exception);
      redirectAttributes.addFlashAttribute("error", "サバイバーマーク投稿に失敗しました。サーバーログを確認してください。");
    }
    return "redirect:/maintenance/survivor-mark/test";
  }

  private String json(Object value) {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (Exception exception) {
      log.warn("Survivor Mark candidate JSON preview could not be generated.", exception);
      return "候補JSONを生成できませんでした。サーバーログを確認してください。";
    }
  }
}
