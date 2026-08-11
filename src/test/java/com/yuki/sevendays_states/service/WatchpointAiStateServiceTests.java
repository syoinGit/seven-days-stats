package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.entity.AiPostType;
import com.yuki.sevendays_states.entity.T_WatchpointAiMemoryHistory;
import com.yuki.sevendays_states.entity.T_WatchpointAiState;
import com.yuki.sevendays_states.repository.T_AiCommentRepository;
import com.yuki.sevendays_states.repository.T_WatchpointAiMemoryHistoryRepository;
import com.yuki.sevendays_states.repository.T_WatchpointAiStateRepository;
import com.yuki.sevendays_states.web.WatchpointAiObservationService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WatchpointAiStateServiceTests {

  @Test
  void publishedObservationUpdatesBoundedMemoryEmotionsAndHistory() {
    T_WatchpointAiStateRepository states = mock(T_WatchpointAiStateRepository.class);
    T_WatchpointAiMemoryHistoryRepository histories =
        mock(T_WatchpointAiMemoryHistoryRepository.class);
    T_AiCommentRepository comments = mock(T_AiCommentRepository.class);
    T_WatchpointAiState state = state();
    when(states.findById(1L)).thenReturn(Optional.of(state));
    WatchpointAiStateService service = new WatchpointAiStateService(states, histories, comments);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var observation = new WatchpointAiObservationService.Observation(
        null, null, null,
        new WatchpointAiObservationService.ActivityTotals(
            1, 0, 10, 2, 1, 1, BigDecimal.TEN, BigDecimal.ZERO),
        null, List.of(), List.of("病院", "書店"), List.of(), null);
    var request = new WatchpointAiObservationService.AnalysisRequest(
        "v1", now, "bedrock", "prompt", "task", null, observation);
    var published = new AiCommentService.AiCommentEntry(
        42L, null, "title", "生存者は病院から帰還した。", now,
        WatchpointAiPublishingService.SOURCE_TYPE, null, List.of());

    service.recordPublished(published, AiPostType.NORMAL, request);

    assertThat(state.getMemorySummary()).contains("[観測] 生存者は病院から帰還した。");
    assertThat(state.getAlertness()).isEqualTo(65);
    assertThat(state.getCuriosity()).isEqualTo(44);
    assertThat(state.getEmpathy()).isEqualTo(39);
    assertThat(state.getTension()).isEqualTo(41);
    assertThat(state.getHope()).isEqualTo(50);
    assertThat(state.getLastPostedAt()).isEqualTo(now);
    ArgumentCaptor<T_WatchpointAiMemoryHistory> historyCaptor =
        ArgumentCaptor.forClass(T_WatchpointAiMemoryHistory.class);
    verify(histories).save(historyCaptor.capture());
    assertThat(historyCaptor.getValue().getSourceCommentId()).isEqualTo(42L);
    assertThat(historyCaptor.getValue().getEmotionsAfter()).contains("alertness=65");
  }

  @Test
  void promptContextIncludesCurrentMemoryAndEmotionGauges() {
    T_WatchpointAiStateRepository states = mock(T_WatchpointAiStateRepository.class);
    T_WatchpointAiState state = state();
    state.setMemorySummary("[観測] 古い病院を監視した。");
    when(states.findById(1L)).thenReturn(Optional.of(state));
    WatchpointAiStateService service = new WatchpointAiStateService(
        states, mock(T_WatchpointAiMemoryHistoryRepository.class),
        mock(T_AiCommentRepository.class));

    assertThat(service.promptContext())
        .contains("警戒心: 50/100", "好奇心: 40/100", "古い病院を監視した");
  }

  private T_WatchpointAiState state() {
    T_WatchpointAiState state = new T_WatchpointAiState();
    state.setId(1L);
    state.setAgentId(1L);
    state.setMemorySummary("");
    state.setAlertness(50);
    state.setCuriosity(40);
    state.setEmpathy(35);
    state.setTension(30);
    state.setHope(50);
    return state;
  }
}
