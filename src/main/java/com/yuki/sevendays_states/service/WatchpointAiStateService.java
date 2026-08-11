package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.AiPostType;
import com.yuki.sevendays_states.entity.T_AiComment;
import com.yuki.sevendays_states.entity.T_WatchpointAiMemoryHistory;
import com.yuki.sevendays_states.entity.T_WatchpointAiState;
import com.yuki.sevendays_states.repository.T_AiCommentRepository;
import com.yuki.sevendays_states.repository.T_WatchpointAiMemoryHistoryRepository;
import com.yuki.sevendays_states.repository.T_WatchpointAiStateRepository;
import com.yuki.sevendays_states.web.WatchpointAiObservationService.AnalysisRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintains WATCHPOINT's bounded rolling memory and deterministic emotional state. */
@Service
@RequiredArgsConstructor
public class WatchpointAiStateService {
  private static final long STATE_ID = 1L;
  private static final int MAX_MEMORY_CHARACTERS = 1000;
  private static final int MAX_MEMORY_ITEMS = 8;

  private final T_WatchpointAiStateRepository stateRepository;
  private final T_WatchpointAiMemoryHistoryRepository historyRepository;
  private final T_AiCommentRepository commentRepository;

  @Transactional
  public String promptContext() {
    T_WatchpointAiState state = state();
    if (state.getMemorySummary().isBlank()) {
      state.setMemorySummary(memoryFromExistingPosts());
      stateRepository.save(state);
    }
    return """
        # WATCHPOINTの現在状態
        警戒心: %d/100
        好奇心: %d/100
        共感: %d/100
        緊張: %d/100
        希望: %d/100

        # 観測記憶
        %s

        感情値は語調の強弱だけに使い、観測されていない事実の根拠にはしません。
        """.formatted(
        state.getAlertness(), state.getCuriosity(), state.getEmpathy(),
        state.getTension(), state.getHope(),
        state.getMemorySummary().isBlank() ? "まだ長期記憶はありません。" : state.getMemorySummary());
  }

  @Transactional
  public void recordPublished(
      AiCommentService.AiCommentEntry comment, AiPostType postType, AnalysisRequest request) {
    T_WatchpointAiState state = state();
    String memoryBefore = state.getMemorySummary();
    String emotionsBefore = emotions(state);

    state.setMemorySummary(appendMemory(memoryBefore, postType, comment.body()));
    var totals = request.observation() == null ? null : request.observation().currentTotals();
    int deaths = totals == null ? 0 : safeInt(totals.deaths());
    int hordes = totals == null ? 0 : safeInt(totals.hordeEvents());
    int sleepers = totals == null ? 0 : safeInt(totals.sleeperEncounters());
    int kills = totals == null ? 0 : safeInt(totals.kills());
    int pois = request.observation() == null || request.observation().visitedPois() == null
        ? 0 : request.observation().visitedPois().size();

    state.setAlertness(gauge(toward(state.getAlertness(), 50) + deaths * 5 + hordes * 8
        + Math.min(5, sleepers)));
    state.setCuriosity(gauge(toward(state.getCuriosity(), 40) + Math.min(10, pois * 2)));
    state.setEmpathy(gauge(toward(state.getEmpathy(), 35) + deaths * 4));
    state.setTension(gauge(toward(state.getTension(), 30) + deaths * 5 + hordes * 6));
    state.setHope(gauge(toward(state.getHope(), 50) + Math.min(5, kills / 5) - deaths * 2));
    state.setLastObservedAt(request.generatedAt());
    state.setLastPostedAt(comment.publishedAt());
    stateRepository.save(state);

    T_WatchpointAiMemoryHistory history = new T_WatchpointAiMemoryHistory();
    history.setStateId(state.getId());
    history.setSourceCommentId(comment.id());
    history.setPostType(postType.name());
    history.setMemoryBefore(memoryBefore);
    history.setMemoryAfter(state.getMemorySummary());
    history.setEmotionsBefore(emotionsBefore);
    history.setEmotionsAfter(emotions(state));
    history.setChangeReason("published observation: deaths=%d, hordes=%d, kills=%d, pois=%d"
        .formatted(deaths, hordes, kills, pois));
    history.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    historyRepository.save(history);
  }

  private T_WatchpointAiState state() {
    return stateRepository.findById(STATE_ID)
        .orElseThrow(() -> new IllegalStateException("WATCHPOINT AI state is missing."));
  }

  private String memoryFromExistingPosts() {
    List<T_AiComment> existing = new ArrayList<>(
        commentRepository.findTop20BySourceTypeOrderByPublishedAtDesc(
            WatchpointAiPublishingService.SOURCE_TYPE));
    Collections.reverse(existing);
    String memory = "";
    for (T_AiComment comment : existing) {
      memory = appendMemory(memory, parsePostType(comment.getPostType()), comment.getBody());
    }
    return memory;
  }

  private String appendMemory(String current, AiPostType postType, String body) {
    List<String> items = new ArrayList<>();
    if (current != null && !current.isBlank()) {
      items.addAll(current.lines().filter(line -> !line.isBlank()).toList());
    }
    String normalized = body == null ? "" : body.replaceAll("[\\r\\n]+", " ").strip();
    if (!normalized.isBlank()) {
      items.add("[%s] %s".formatted(postType.displayLabel(), normalized));
    }
    while (items.size() > MAX_MEMORY_ITEMS) items.removeFirst();
    String result = String.join("\n", items);
    while (result.length() > MAX_MEMORY_CHARACTERS && items.size() > 1) {
      items.removeFirst();
      result = String.join("\n", items);
    }
    return result.length() <= MAX_MEMORY_CHARACTERS
        ? result : result.substring(result.length() - MAX_MEMORY_CHARACTERS);
  }

  private AiPostType parsePostType(String value) {
    try {
      return AiPostType.valueOf(value);
    } catch (RuntimeException exception) {
      return AiPostType.NORMAL;
    }
  }

  private String emotions(T_WatchpointAiState state) {
    return "alertness=%d,curiosity=%d,empathy=%d,tension=%d,hope=%d".formatted(
        state.getAlertness(), state.getCuriosity(), state.getEmpathy(),
        state.getTension(), state.getHope());
  }

  private int toward(int value, int baseline) {
    return value == baseline ? value : value + Integer.signum(baseline - value) * 2;
  }

  private int gauge(int value) {
    return Math.max(0, Math.min(100, value));
  }

  private int safeInt(long value) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
  }
}
