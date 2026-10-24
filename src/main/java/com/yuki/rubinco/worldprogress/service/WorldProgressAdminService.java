package com.yuki.rubinco.worldprogress.service;

import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.entity.M_GuideArticle;
import com.yuki.rubinco.worldprogress.entity.M_GuideRule;
import com.yuki.rubinco.worldprogress.entity.M_ProgressDefinition;
import com.yuki.rubinco.worldprogress.entity.T_WorldImportRun;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import com.yuki.rubinco.worldprogress.repository.M_GuideArticleRepository;
import com.yuki.rubinco.worldprogress.repository.M_GuideRuleRepository;
import com.yuki.rubinco.worldprogress.repository.M_ProgressDefinitionRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldImportRunRepository;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-only maintenance. Every method re-checks the role, not only the HTTP route. */
@Service
@RequiredArgsConstructor
@PreAuthorize("@adminAccess.currentUserIsAdmin()")
public class WorldProgressAdminService {
  private static final DisplayTimeFormatter TIME = new DisplayTimeFormatter();
  /** Per-definition article condition, as offered by the maintenance form. */
  public static final Map<String, String> CONDITIONS = Map.of(
      "SHOW:ACHIEVED", "達成済みなら表示", "SHOW:NOT_ACHIEVED", "未達なら表示",
      "HIDE:ACHIEVED", "達成済みなら非表示", "HIDE:NOT_ACHIEVED", "未達なら非表示");

  private final M_GameWorldRepository worlds;
  private final M_ProgressDefinitionRepository definitions;
  private final M_GuideArticleRepository articles;
  private final M_GuideRuleRepository rules;
  private final T_WorldImportRunRepository runs;

  public record RunView(Long id, String trigger, String status, String requestedBy,
      String requestedAt, String finishedAt, Long durationMs, Long sourceSize,
      Long decompressedBytes, String sha256Prefix, Integer containerVersion,
      String parserVersion, Long snapshotId, String errorCode, String errorMessage) { }

  public record ArticleForm(String title, String body, String gameVersion, String referenceUrl,
      String referenceLabel, LocalDate verifiedOn, boolean spoiler, boolean published,
      int sortOrder, Map<Long, String> conditions) { }

  public record ArticleView(M_GuideArticle article, Map<Long, String> conditions) { }

  @Transactional(readOnly = true)
  public Page<RunView> runs(String gameId, int page) {
    return runs.findByGameIdOrderByRequestedAtDescIdDesc(gameId, PageRequest.of(page, 30))
        .map(WorldProgressAdminService::view);
  }

  @Transactional(readOnly = true)
  public List<M_GameWorld> worlds(String gameId) {
    return worlds.findByGameIdOrderByWorldKeyAscGenerationDesc(gameId);
  }

  @Transactional(readOnly = true)
  public List<M_ProgressDefinition> definitions(String gameId) {
    return definitions.findByGameIdOrderBySortOrderAscIdAsc(gameId);
  }

  @Transactional(readOnly = true)
  public List<ArticleView> articles(String gameId) {
    List<M_GuideArticle> all = articles.findByGameIdOrderBySortOrderAscIdAsc(gameId);
    Map<Long, List<M_GuideRule>> byArticle = all.isEmpty() ? Map.of()
        : rules.findByGuideArticleIdIn(all.stream().map(M_GuideArticle::getId).toList()).stream()
            .collect(Collectors.groupingBy(M_GuideRule::getGuideArticleId));
    return all.stream().map(article -> new ArticleView(article,
        byArticle.getOrDefault(article.getId(), List.of()).stream().collect(Collectors.toMap(
            M_GuideRule::getProgressDefinitionId,
            rule -> rule.getRuleType() + ":" + rule.getRequiredState(), (a, b) -> a)))).toList();
  }

  @Transactional
  public void updateWorld(String gameId, Long worldId, String displayName, String configuredNote,
      boolean published) {
    M_GameWorld world = world(gameId, worldId);
    world.setDisplayName(required(displayName, "ワールド表示名", 80));
    world.setConfiguredNote(optional(configuredNote, 400));
    world.setPublished(published);
    worlds.save(world);
  }

  /** A replaced world starts a fresh generation; its history is never appended to the old one. */
  @Transactional
  public M_GameWorld startNewGeneration(String gameId, String displayName, String configuredNote) {
    List<M_GameWorld> locked = worlds.findByGameIdForUpdate(gameId);
    M_GameWorld current = locked.stream().filter(M_GameWorld::isActive).findFirst().orElse(null);
    String key = current == null ? "main" : current.getWorldKey();
    int generation = locked.stream().filter(world -> world.getWorldKey().equals(key))
        .mapToInt(M_GameWorld::getGeneration).max().orElse(0) + 1;
    locked.forEach(world -> {
      if (world.isActive()) {
        world.setActive(false);
        worlds.save(world);
      }
    });
    worlds.flush();
    M_GameWorld next = new M_GameWorld();
    next.setGameId(gameId);
    next.setWorldKey(key);
    next.setGeneration(generation);
    next.setDisplayName(required(displayName, "ワールド表示名", 80));
    next.setConfiguredNote(optional(configuredNote, 400));
    next.setPublished(current == null || current.isPublished());
    next.setActive(true);
    next.setActivatedAt(OffsetDateTime.now());
    return worlds.save(next);
  }

  @Transactional
  public void activate(String gameId, Long worldId) {
    List<M_GameWorld> locked = worlds.findByGameIdForUpdate(gameId);
    M_GameWorld target = locked.stream().filter(world -> world.getId().equals(worldId))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("ワールドが見つかりません。"));
    locked.forEach(world -> {
      if (world.isActive() && !world.getId().equals(worldId)) {
        world.setActive(false);
        worlds.save(world);
      }
    });
    target.setActive(true);
    target.setActivatedAt(OffsetDateTime.now());
    worlds.save(target);
  }

  @Transactional
  public void updateDefinition(String gameId, Long definitionId, String displayName,
      String description, boolean spoiler) {
    M_ProgressDefinition definition = definitions.findById(definitionId)
        .filter(candidate -> candidate.getGameId().equals(gameId))
        .orElseThrow(() -> new IllegalArgumentException("進捗項目が見つかりません。"));
    definition.setDisplayName(required(displayName, "表示名", 80));
    definition.setDescription(optional(description, 400));
    definition.setSpoiler(spoiler);
    definitions.save(definition);
  }

  @Transactional
  public M_GuideArticle saveArticle(String gameId, Long articleId, ArticleForm form) {
    M_GuideArticle article = articleId == null ? new M_GuideArticle()
        : articles.findById(articleId).filter(candidate -> candidate.getGameId().equals(gameId))
            .orElseThrow(() -> new IllegalArgumentException("記事が見つかりません。"));
    article.setGameId(gameId);
    article.setTitle(required(form.title(), "タイトル", 120));
    article.setBody(required(form.body(), "本文", 20000));
    article.setGameVersion(optional(form.gameVersion(), 40));
    article.setReferenceUrl(referenceUrl(form.referenceUrl()));
    article.setReferenceLabel(optional(form.referenceLabel(), 80));
    article.setVerifiedOn(form.verifiedOn());
    article.setSpoiler(form.spoiler());
    article.setPublished(form.published());
    article.setSortOrder(Math.max(-100000, Math.min(100000, form.sortOrder())));
    article = articles.save(article);

    Set<Long> known = definitions.findByGameIdOrderBySortOrderAscIdAsc(gameId).stream()
        .filter(M_ProgressDefinition::isSupported).map(M_ProgressDefinition::getId)
        .collect(Collectors.toSet());
    rules.deleteByGuideArticleId(article.getId());
    rules.flush();
    for (var entry : form.conditions().entrySet()) {
      if (entry.getValue() == null || entry.getValue().isBlank()) continue;
      if (!known.contains(entry.getKey()) || !CONDITIONS.containsKey(entry.getValue())) {
        throw new IllegalArgumentException("表示条件が不正です。");
      }
      String[] parts = entry.getValue().split(":");
      M_GuideRule rule = new M_GuideRule();
      rule.setGuideArticleId(article.getId());
      rule.setProgressDefinitionId(entry.getKey());
      rule.setRuleType(parts[0]);
      rule.setRequiredState(parts[1]);
      rules.save(rule);
    }
    return article;
  }

  @Transactional
  public void deleteArticle(String gameId, Long articleId) {
    articles.findById(articleId).filter(article -> article.getGameId().equals(gameId))
        .ifPresent(article -> {
          rules.deleteByGuideArticleId(article.getId());
          articles.delete(article);
        });
  }

  private M_GameWorld world(String gameId, Long worldId) {
    return worlds.findById(worldId).filter(world -> world.getGameId().equals(gameId))
        .orElseThrow(() -> new IllegalArgumentException("ワールドが見つかりません。"));
  }

  private static RunView view(T_WorldImportRun run) {
    return new RunView(run.getId(), run.getTriggerType(), run.getStatus(), run.getRequestedBy(),
        TIME.format(run.getRequestedAt()), TIME.format(run.getFinishedAt()), run.getDurationMs(),
        run.getSourceSize(), run.getDecompressedBytes(),
        run.getSourceSha256() == null ? null : run.getSourceSha256().substring(0, 12),
        run.getContainerVersion(), run.getParserVersion(), run.getWorldSnapshotId(),
        run.getErrorCode(), run.getErrorMessage());
  }

  /** Links are rendered as hrefs, so only absolute http(s) URLs are accepted. */
  static String referenceUrl(String value) {
    String url = optional(value, 300);
    if (url == null) return null;
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null || uri.getUserInfo() != null
          || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException("参考リンクは http(s) のURLを指定してください。");
      }
      return url;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("参考リンクは http(s) のURLを指定してください。");
    }
  }

  private static String required(String value, String label, int max) {
    String text = optional(value, max);
    if (text == null) throw new IllegalArgumentException(label + "を入力してください。");
    return text;
  }

  private static String optional(String value, int max) {
    if (value == null || value.isBlank()) return null;
    String text = value.strip();
    if (text.length() > max) throw new IllegalArgumentException(max + "文字以内で入力してください。");
    return text;
  }
}
