package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.web.DiaryMaintenanceService;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WatchpointDiaryPublishingServiceTests {

  @Test
  void masterFlagDisablesManualAiDiaryGeneration() {
    DiaryMaintenanceService maintenance = mock(DiaryMaintenanceService.class);
    BedrockDiaryClient bedrock = mock(BedrockDiaryClient.class);
    AiCommentService comments = mock(AiCommentService.class);
    WatchpointDiaryPublishingService service = new WatchpointDiaryPublishingService(
        new AiAnalysisProperties(false, 30, 60, "", "ap-northeast-1", "model", 240,
            Duration.ofMinutes(30), Duration.ofMinutes(1), 10),
        maintenance, bedrock, comments);
    LocalDate date = LocalDate.of(2026, 8, 10);

    var result = service.publishNow(date);

    assertThat(result.status()).isEqualTo(WatchpointDiaryPublishingService.PublishStatus.DISABLED);
    verify(maintenance, never()).packet(date);
    verify(bedrock, never()).generate(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
  }
}
