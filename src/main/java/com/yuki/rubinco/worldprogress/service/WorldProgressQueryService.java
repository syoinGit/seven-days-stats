package com.yuki.rubinco.worldprogress.service;

import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.entity.M_GuideArticle;
import com.yuki.rubinco.worldprogress.entity.M_GuideRule;
import com.yuki.rubinco.worldprogress.entity.M_ProgressDefinition;
import com.yuki.rubinco.worldprogress.entity.T_ProgressEvent;
import com.yuki.rubinco.worldprogress.entity.T_ProgressState;
import com.yuki.rubinco.worldprogress.entity.T_WorldImportRun;
import com.yuki.rubinco.worldprogress.entity.T_WorldSnapshot;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import com.yuki.rubinco.worldprogress.repository.M_GuideArticleRepository;
import com.yuki.rubinco.worldprogress.repository.M_GuideRuleRepository;
import com.yuki.rubinco.worldprogress.repository.M_ProgressDefinitionRepository;
import com.yuki.rubinco.worldprogress.repository.T_ProgressEventRepository;
import com.yuki.rubinco.worldprogress.repository.T_ProgressStateRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldImportRunRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldSnapshotRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorldProgressQueryService {
  private static final DisplayTimeFormatter TIME = new DisplayTimeFormatter();
  private static final String MASK = "？？？";
  private static final List<String> SUCCESSFUL_RUNS = List.of("SUCCEEDED", "UNCHANGED");

  private final M_GameWorldRepository worlds;
  private final T_WorldSnapshotRepository snapshots;
  private final M_ProgressDefinitionRepository definitions;
  private final T_ProgressStateRepository states;
  private final T_ProgressEventRepository events;
  private final T_WorldImportRunRepository runs;
  private final M_GuideArticleRepository articles;
  private final M_GuideRuleRepository rules;

  public record Overview(
      boolean worldConfigured, boolean visible, String worldName, String configuredNote,
      int generation, boolean hasSnapshot, String lastCheckedLabel, String freshnessLabel,
      boolean stale, Integer containerVersion, String parserVersion,
      long achievedCount, long supportedCount, List<ProgressItem> items,
      List<EventItem> recentEvents, List<GuideItem> guides, int hiddenSpoilerGuides) { }

  public record ProgressItem(String label, String category, String state, String stateLabel,
      boolean supported, boolean masked, String description) { }

  public record EventItem(String label, boolean masked, String previousLabel, String newLabel,
      String kind, String kindLabel, OffsetDateTime observedAt, String observedLabel) { }

  public record GuideItem(String title, String body, String gameVersion, String referenceUrl,
      String referenceLabel, LocalDate verifiedOn, boolean spoiler) { }

  @Transactional(readOnly = true)
  public Overview overview(String gameId, boolean admin, boolean showSpoilers,
      Duration staleAfter) {
    M_GameWorld world = worlds.findFirstByGameIdAndActiveTrue(gameId).orElse(null);
    if (world == null) {
      return new Overview(false, false, null, null, 0, false, null, null, false, null, null,
          0, 0, List.of(), List.of(), List.of(), 0);
    }
    boolean visible = world.isPublished() || admin;
    if (!visible) {
      return new Overview(true, false, world.getDisplayName(), null, world.getGeneration(),
          false, null, null, false, null, null, 0, 0, List.of(), List.of(), List.of(), 0);
    }
    List<M_ProgressDefinition> gameDefinitions =
        definitions.findByGameIdOrderBySortOrderAscIdAsc(gameId);
    T_WorldSnapshot snapshot = world.getLatestSnapshotId() == null ? null
        : snapshots.findById(world.getLatestSnapshotId()).orElse(null);
    Map<Long, ProgressState> current = snapshot == null ? Map.of()
        : states.findByWorldSnapshotId(snapshot.getId()).stream().collect(Collectors.toMap(
            T_ProgressState::getProgressDefinitionId, row -> ProgressState.from(row.getState())));

    List<ProgressItem> items = gameDefinitions.stream().map(definition -> {
      ProgressState state = current.getOrDefault(definition.getId(), ProgressState.UNKNOWN);
      boolean masked = masked(definition, state, showSpoilers);
      return new ProgressItem(masked ? MASK : definition.getDisplayName(), definition.getCategory(),
          state.name(), definition.isSupported() ? state.label() : "取得対象外",
          definition.isSupported(), masked, masked ? null : definition.getDescription());
    }).toList();
    long supported = gameDefinitions.stream().filter(M_ProgressDefinition::isSupported).count();
    long achieved = gameDefinitions.stream().filter(M_ProgressDefinition::isSupported)
        .filter(definition -> current.get(definition.getId()) == ProgressState.ACHIEVED).count();

    OffsetDateTime lastChecked = runs.findFirstByGameWorldIdAndStatusInOrderByFinishedAtDescIdDesc(
        world.getId(), SUCCESSFUL_RUNS).map(T_WorldImportRun::getFinishedAt)
        .orElse(snapshot == null ? null : snapshot.getParsedAt());
    boolean stale = lastChecked == null
        || lastChecked.isBefore(OffsetDateTime.now().minus(staleAfter));

    List<GuideItem> guides = new java.util.ArrayList<>();
    int hiddenSpoilers = 0;
    List<M_GuideArticle> published = articles.findByGameIdOrderBySortOrderAscIdAsc(gameId).stream()
        .filter(M_GuideArticle::isPublished).toList();
    Map<Long, List<M_GuideRule>> rulesByArticle = published.isEmpty() ? Map.of()
        : rules.findByGuideArticleIdIn(published.stream().map(M_GuideArticle::getId).toList())
            .stream().collect(Collectors.groupingBy(M_GuideRule::getGuideArticleId));
    for (M_GuideArticle article : published) {
      if (!GuideRules.visible(rulesByArticle.getOrDefault(article.getId(), List.of()), current)) {
        continue;
      }
      if (article.isSpoiler() && !showSpoilers) {
        hiddenSpoilers++;
        continue;
      }
      guides.add(new GuideItem(article.getTitle(), article.getBody(), article.getGameVersion(),
          article.getReferenceUrl(), article.getReferenceLabel(), article.getVerifiedOn(),
          article.isSpoiler()));
    }

    return new Overview(true, true, world.getDisplayName(), world.getConfiguredNote(),
        world.getGeneration(), snapshot != null, lastChecked == null ? null : TIME.format(lastChecked),
        freshness(lastChecked), stale, snapshot == null ? null : snapshot.getContainerVersion(),
        snapshot == null ? null : snapshot.getParserVersion(), achieved, supported, items,
        history(gameId, admin, showSpoilers, 0, 5).getContent(), List.copyOf(guides), hiddenSpoilers);
  }

  @Transactional(readOnly = true)
  public Page<EventItem> history(String gameId, boolean admin, boolean showSpoilers, int page,
      int size) {
    M_GameWorld world = worlds.findFirstByGameIdAndActiveTrue(gameId).orElse(null);
    if (world == null || (!world.isPublished() && !admin)) {
      return Page.empty(PageRequest.of(page, size));
    }
    Map<Long, M_ProgressDefinition> byId = definitions.findByGameIdOrderBySortOrderAscIdAsc(gameId)
        .stream().collect(Collectors.toMap(M_ProgressDefinition::getId, Function.identity()));
    return events.findByGameWorldIdOrderByFirstObservedAtDescIdDesc(world.getId(),
        PageRequest.of(page, size)).map(event -> eventItem(event, byId, showSpoilers));
  }

  private EventItem eventItem(T_ProgressEvent event, Map<Long, M_ProgressDefinition> byId,
      boolean showSpoilers) {
    M_ProgressDefinition definition = byId.get(event.getProgressDefinitionId());
    ProgressState state = ProgressState.from(event.getNewState());
    boolean masked = definition == null || masked(definition, state, showSpoilers);
    return new EventItem(masked ? MASK : definition.getDisplayName(), masked,
        event.getPreviousState() == null ? "—" : ProgressState.from(event.getPreviousState()).label(),
        state.label(), event.getChangeKind(), kindLabel(event.getChangeKind()),
        event.getFirstObservedAt(), TIME.format(event.getFirstObservedAt()));
  }

  /** Unreached spoiler items stay hidden; achieved ones are no longer secrets in this world. */
  private static boolean masked(M_ProgressDefinition definition, ProgressState state,
      boolean showSpoilers) {
    return definition.isSpoiler() && state != ProgressState.ACHIEVED && !showSpoilers;
  }

  static String kindLabel(String kind) {
    return switch (kind) {
      case "INITIAL" -> "初回観測";
      case "CHANGED" -> "変化を確認";
      case "REGRESSED" -> "状態が後退";
      case "PARSER_CHANGE" -> "解析方式の更新による変更";
      case "NEWLY_SUPPORTED" -> "新たに取得対象化";
      default -> kind;
    };
  }

  private static String freshness(OffsetDateTime lastChecked) {
    if (lastChecked == null) return "未取得";
    long minutes = Math.max(0, Duration.between(lastChecked, OffsetDateTime.now()).toMinutes());
    if (minutes < 1) return "1分以内";
    if (minutes < 60) return minutes + "分前";
    if (minutes < 60 * 48) return (minutes / 60) + "時間前";
    return (minutes / (60 * 24)) + "日前";
  }
}
