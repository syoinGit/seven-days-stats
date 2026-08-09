package com.yuki.sevendays_states.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import com.yuki.sevendays_states.service.AiCommentService;
import com.yuki.sevendays_states.service.SurvivorKarenPublishingService;
import com.yuki.sevendays_states.service.SurvivorMarkPublishingService;
import com.yuki.sevendays_states.service.WatchpointAiPublishingService;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import tools.jackson.databind.ObjectMapper;

class AiAnalysisMaintenanceControllerTests {

  private WatchpointAiObservationService observationService;
  private AiCommentService aiCommentService;
  private SurvivorKarenPublishingService karenPublishingService;
  private SurvivorMarkPublishingService markPublishingService;
  private AiAnalysisMaintenanceController controller;

  @BeforeEach
  void setUp() {
    AiAnalysisProperties properties = new AiAnalysisProperties(
        30, 60, "", true, "ap-northeast-1", "claude-test", 240, 30, 0);
    SurvivorKarenProperties karenProperties = new SurvivorKarenProperties(
        true, true, true, 3, 12, "us-east-1", "nova-canvas-test",
        "watchpoint-images", "watchpoint/posts/survivor-karen", "");
    observationService = mock(WatchpointAiObservationService.class);
    WatchpointAiPublishingService publishingService =
        mock(WatchpointAiPublishingService.class);
    aiCommentService = mock(AiCommentService.class);
    karenPublishingService = mock(SurvivorKarenPublishingService.class);
    markPublishingService = mock(SurvivorMarkPublishingService.class);
    controller = new AiAnalysisMaintenanceController(
        properties, observationService, publishingService, aiCommentService,
        karenProperties, karenPublishingService,
        new SurvivorMarkProperties(true, true, true, 14, 2, 5, 30),
        markPublishingService, new ObjectMapper());
  }

  @Test
  void testPageExposesKarenGenerationSettings() {
    when(aiCommentService.latestBySourceType(WatchpointAiPublishingService.SOURCE_TYPE))
        .thenReturn(Optional.empty());
    ConcurrentModel model = new ConcurrentModel();

    assertThat(controller.testPage(model)).isEqualTo("ai-analysis-test");
    assertThat(model).containsEntry("karenEnabled", true);
    assertThat(model).containsEntry("karenImageEnabled", true);
    assertThat(model).containsEntry("karenAwsRegion", "us-east-1");
    assertThat(model).containsEntry("karenModelId", "nova-canvas-test");
    assertThat(model).containsEntry("markEnabled", true);
  }

  @Test
  void manualKarenGenerationReportsAnImagePost() {
    when(karenPublishingService.publishTodayIfMissing()).thenReturn(
        new SurvivorKarenPublishingService.PublishResult(
            SurvivorKarenPublishingService.PublishStatus.PUBLISHED,
            LocalDate.of(2026, 8, 9), null, true));
    RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

    assertThat(controller.generateKaren(redirect))
        .isEqualTo("redirect:/maintenance/ai-analysis/test");
    assertThat(redirect.getFlashAttributes().get("notice"))
        .isEqualTo("Karenが画像付きの新しい投稿を公開しました。");
  }

  @Test
  void manualKarenGenerationDoesNotTreatAnExistingDailyPostAsAnError() {
    when(karenPublishingService.publishTodayIfMissing()).thenReturn(
        new SurvivorKarenPublishingService.PublishResult(
            SurvivorKarenPublishingService.PublishStatus.ALREADY_PUBLISHED,
            LocalDate.of(2026, 8, 9), null, false));
    RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

    controller.generateKaren(redirect);

    assertThat(redirect.getFlashAttributes().get("notice"))
        .isEqualTo("Karenは今日の投稿を既に公開済みです。");
    assertThat(redirect.getFlashAttributes()).doesNotContainKey("error");
  }
}
