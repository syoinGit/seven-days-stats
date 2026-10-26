package com.yuki.rubinco.watchpoint.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.yuki.rubinco.watchpoint.ai.AiCommentService;
import com.yuki.rubinco.watchpoint.ai.WatchpointAiPublishingService;
import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import com.yuki.rubinco.watchpoint.readmodel.WatchpointAiObservationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import tools.jackson.databind.ObjectMapper;

class AiAnalysisMaintenanceControllerTests {

  private AiCommentService aiCommentService;
  private WatchpointAiPublishingService publishingService;
  private AiAnalysisMaintenanceController controller;

  @BeforeEach
  void setUp() {
    AiAnalysisProperties properties = new AiAnalysisProperties(true,
        30, 60, "", "ap-northeast-1", "claude-test", 240,
        java.time.Duration.ofMinutes(30), java.time.Duration.ZERO, 10);
    WatchpointAiObservationService observationService = mock(WatchpointAiObservationService.class);
    publishingService = mock(WatchpointAiPublishingService.class);
    aiCommentService = mock(AiCommentService.class);
    controller = new AiAnalysisMaintenanceController(
        properties, observationService, publishingService, aiCommentService, new ObjectMapper(),
        new com.yuki.rubinco.worldprogress.ai.WorldAiProperties(false, java.time.Duration.ofHours(1), 300),
        mock(com.yuki.rubinco.watchpoint.ai.AiCallBudget.class));
  }

  @Test
  void testPageExposesWatchpointSettingsOnly() {
    when(aiCommentService.latestBySourceType(WatchpointAiPublishingService.SOURCE_TYPE))
        .thenReturn(Optional.empty());
    ConcurrentModel model = new ConcurrentModel();

    assertThat(controller.testPage(model)).isEqualTo("ai-analysis-test");
    assertThat(model).containsEntry("modelId", "claude-test");
    assertThat(model.asMap().keySet()).noneMatch(key -> key.startsWith("karen"));
  }

  @Test
  void manualGenerationReportsADisabledAi() {
    when(publishingService.publishNow()).thenReturn(new WatchpointAiPublishingService.PublishResult(
        WatchpointAiPublishingService.PublishStatus.DISABLED, null));
    RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

    assertThat(controller.generate(redirect)).isEqualTo("redirect:/maintenance/ai-analysis/test");
    assertThat(redirect.getFlashAttributes()).containsKey("error");
  }
}
