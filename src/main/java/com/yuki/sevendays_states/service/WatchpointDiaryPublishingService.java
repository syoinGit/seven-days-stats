package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.web.DiaryMaintenanceService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Generates at most one Bedrock diary per date while keeping manual maintenance available. */
@Service
@RequiredArgsConstructor
public class WatchpointDiaryPublishingService {

  private final AiAnalysisProperties properties;
  private final DiaryMaintenanceService diaryMaintenanceService;
  private final BedrockDiaryClient bedrockDiaryClient;
  private final AiCommentService aiCommentService;

  public PublishResult publishIfMissing(LocalDate date) {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    if (aiCommentService.findByDiaryDate(date).isPresent()) {
      return new PublishResult(PublishStatus.ALREADY_EXISTS, null);
    }
    return publishNow(date);
  }

  public PublishResult publishNow(LocalDate date) {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    DiaryMaintenanceService.DiaryPacket packet = diaryMaintenanceService.packet(date);
    var previousTags = aiCommentService.findByDiaryDate(date.minusDays(1))
        .map(AiCommentService.AiCommentEntry::tags)
        .orElseGet(java.util.List::of);
    BedrockDiaryClient.GeneratedDiary generated =
        bedrockDiaryClient.generate(packet.generationData(), previousTags);
    var saved = aiCommentService.publishGeneratedDiary(
        date, generated.title(), generated.summary(), generated.tags(), generated.body());
    return new PublishResult(PublishStatus.PUBLISHED, saved);
  }

  public enum PublishStatus { PUBLISHED, ALREADY_EXISTS, DISABLED }

  public record PublishResult(PublishStatus status, AiCommentService.AiCommentEntry diary) {
  }
}
