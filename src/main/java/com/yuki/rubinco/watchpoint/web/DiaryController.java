package com.yuki.rubinco.watchpoint.web;

import com.yuki.rubinco.watchpoint.ai.AiCommentService;
import com.yuki.rubinco.watchpoint.ai.WatchpointDiaryPublishingService;
import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import com.yuki.rubinco.watchpoint.readmodel.DiaryMaintenanceService;
import com.yuki.rubinco.watchpoint.readmodel.DiaryViewService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DiaryController {

  private final AiCommentService aiCommentService;
  private final DiaryMaintenanceService diaryMaintenanceService;
  private final DiaryViewService diaryViewService;
  private final WatchpointDiaryPublishingService diaryPublishingService;
  private final AiAnalysisProperties aiProperties;

  @GetMapping("/diaries")
  public String diaries(Model model) {
    model.addAttribute("diaries", diaryViewService.archive());
    return "diaries";
  }

  @GetMapping("/diaries/{date}")
  public String diary(@PathVariable LocalDate date, Model model) {
    DiaryViewService.DiaryDetail diary = diaryViewService.detail(date)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    model.addAttribute("diary", diary);
    return "diary-detail";
  }

  @GetMapping("/maintenance/diaries")
  public String diaryMaintenance(Model model) {
    model.addAttribute("days", diaryMaintenanceService.days());
    return "diary-maintenance";
  }

  @GetMapping("/maintenance/diaries/{date}")
  public String diaryGenerationData(@PathVariable LocalDate date, Model model) {
    model.addAttribute("packet", diaryMaintenanceService.packet(date));
    model.addAttribute("aiEnabled", aiProperties.enabled());
    return "diary-generation-data";
  }

  @GetMapping("/maintenance/diaries/{date}/edit")
  public String diaryEditor(@PathVariable LocalDate date, Model model) {
    model.addAttribute("packet", diaryMaintenanceService.packet(date));
    return "diary-editor";
  }

  @PostMapping("/maintenance/diaries/{date}/generate")
  public String generateDiary(
      @PathVariable LocalDate date,
      RedirectAttributes redirectAttributes) {
    try {
      var result = diaryPublishingService.publishNow(date);
      boolean published = result.status()
          == WatchpointDiaryPublishingService.PublishStatus.PUBLISHED;
      redirectAttributes.addFlashAttribute(published ? "notice" : "error",
          published ? date + " の日記をWATCHPOINTが作成しました。"
              : "AI日記生成が無効です。設定を確認してください。");
    } catch (RuntimeException exception) {
      log.warn("Failed to generate diary for {}", date, exception);
      redirectAttributes.addFlashAttribute("error", "AI日記を生成できませんでした。ログを確認してください。");
    }
    return "redirect:/maintenance/diaries/" + date;
  }

  @PostMapping("/maintenance/diaries/{date}/edit")
  public String publishDiary(
      @PathVariable LocalDate date,
      @RequestParam String title,
      @RequestParam String body,
      RedirectAttributes redirectAttributes) {
    try {
      aiCommentService.publish(date, title, body);
      redirectAttributes.addFlashAttribute("notice", date + " の冒険日記を登録しました。");
      return "redirect:/maintenance/diaries/" + date;
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      redirectAttributes.addFlashAttribute("draftTitle", title);
      redirectAttributes.addFlashAttribute("draftBody", body);
      return "redirect:/maintenance/diaries/" + date + "/edit";
    }
  }

}
