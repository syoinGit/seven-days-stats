package com.yuki.sevendays_states.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import com.yuki.sevendays_states.service.SurvivorMarkCandidateService;
import com.yuki.sevendays_states.service.SurvivorMarkPublishingService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import tools.jackson.databind.ObjectMapper;

class SurvivorMarkMaintenanceControllerTests {

  @Test
  void testPageShowsTheHistoricalCandidateAndBedrockStatus() {
    SurvivorMarkCandidateService candidates = mock(SurvivorMarkCandidateService.class);
    SurvivorMarkProperties properties = new SurvivorMarkProperties(true, true, true, 20, 2, 5, 30);
    var candidate = new SurvivorMarkCandidateService.Candidate("1:2", 120, 240, 2, 1, 0,
        java.util.List.of("zombieNurseRadiated"), 70, "crack_a_book");
    when(candidates.select(any(), eq(properties))).thenReturn(Optional.of(candidate));
    SurvivorMarkMaintenanceController controller = new SurvivorMarkMaintenanceController(
        properties, new AiAnalysisProperties(30, 60, "", true, "ap-northeast-1", "claude-test", 240, 30, 0),
        candidates, mock(SurvivorMarkPublishingService.class), new ObjectMapper());
    ConcurrentModel model = new ConcurrentModel();

    assertThat(controller.testPage(model)).isEqualTo("survivor-mark-test");
    assertThat(model).containsEntry("markEnabled", true).containsEntry("candidate", candidate);
    assertThat(model.getAttribute("candidateJson")).asString().contains("crack_a_book");
  }
}
