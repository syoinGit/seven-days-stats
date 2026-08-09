package com.yuki.sevendays_states.web;

import com.yuki.sevendays_states.service.AiCommentService;
import com.yuki.sevendays_states.service.CurrentWebAccountService;
import com.yuki.sevendays_states.service.PlayerSocialService;
import com.yuki.sevendays_states.service.PlayerStatusService;
import com.yuki.sevendays_states.service.TimelinePostService;
import com.yuki.sevendays_states.service.WatchpointDiaryPublishingService;
import com.yuki.sevendays_states.entity.TimelinePostType;
import com.yuki.sevendays_states.util.DisplayTimeFormatter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.yuki.sevendays_states.entity.ReactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class DashboardController {

  private static final DateTimeFormatter TIMELINE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int EVENT_WINDOW_MINUTES = 5;
  private static final int INITIAL_TIMELINE_ITEMS = 12;
  private static final int RECENT_POST_MINUTES = 60;
  private static final DisplayTimeFormatter DISPLAY_TIME_FORMATTER = new DisplayTimeFormatter();

  private final DashboardViewService dashboardViewService;
  private final AiCommentService aiCommentService;
  private final DiaryMaintenanceService diaryMaintenanceService;
  private final DiaryViewService diaryViewService;
  private final PlayerStatusService playerStatusService;
  private final CurrentWebAccountService currentAccountService;
  private final PlayerSocialService playerSocialService;
  private final TimelinePostService timelinePostService;
  private final WatchpointDiaryPublishingService diaryPublishingService;

  @GetMapping("/")
  public String landing(Authentication authentication) {
    return currentAccountService.current(authentication).isPresent()
        ? "redirect:/dashboard"
        : "landing";
  }

  @GetMapping("/dashboard")
  public String index(Model model, Authentication authentication) {
    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard(false);
    model.addAttribute("dashboard", dashboard);
    model.addAttribute("timelinePage", timelinePage(timelinePostService.feed(authentication, 0)));
    return "dashboard";
  }

  /**
   * The live dashboard initially renders only one small page. Older entries are fetched as an
   * HTML fragment when the reader reaches the bottom, keeping long-running servers from putting
   * their entire history into one response or DOM tree.
   */
  @GetMapping("/dashboard/timeline")
  public String olderTimeline(
      @RequestParam(defaultValue = "0") int offset,
      Model model,
      Authentication authentication) {
    model.addAttribute("timelinePage", timelinePage(timelinePostService.feed(authentication, offset)));
    return "fragments/timeline :: page";
  }

  @PostMapping("/players/{playerId}/status")
  public String updateStatus(
      @PathVariable Long playerId,
      @RequestParam String status,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var updated = currentAccountService.current(authentication)
        .filter(account -> playerId.equals(account.getPlayerId()))
        .flatMap(account -> playerStatusService.updateByPlayerId(playerId, status, "WEB"));
    redirectAttributes.addFlashAttribute(updated.isPresent() ? "notice" : "error",
        updated.isPresent() ? "ステータスを更新しました。" : "自分のオンライン中プレイヤーだけ更新できます。");
    return "redirect:/players/" + playerId;
  }

  @GetMapping("/community")
  public String community() {
    return "redirect:/dashboard#timeline";
  }

  @PostMapping("/posts")
  public String createPost(
      @RequestParam String body,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var result = playerSocialService.createPost(authentication, body);
    redirectAttributes.addFlashAttribute(result.success() ? "notice" : "error", result.message());
    return "redirect:/dashboard#timeline";
  }

  @PostMapping("/posts/{postId}/like")
  public String toggleLike(
      @PathVariable Long postId,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var result = playerSocialService.toggleLike(authentication, postId);
    redirectAttributes.addFlashAttribute(result.success() ? "notice" : "error", result.message());
    return "redirect:/dashboard#timeline";
  }

  @PostMapping("/posts/{postId}/react")
  public String toggleReaction(
      @PathVariable Long postId,
      @RequestParam String reaction,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var result = playerSocialService.toggleReaction(authentication, postId, reaction);
    redirectAttributes.addFlashAttribute(result.success() ? "notice" : "error", result.message());
    return "redirect:/dashboard#timeline";
  }

  @PostMapping(value = "/posts/{postId}/like.json", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public PlayerSocialService.LikeResult toggleLikeJson(
      @PathVariable Long postId,
      Authentication authentication) {
    return playerSocialService.toggleLike(authentication, postId);
  }

  @PostMapping("/posts/{postId}/delete")
  public String deletePost(
      @PathVariable Long postId,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var result = playerSocialService.deletePost(authentication, postId);
    redirectAttributes.addFlashAttribute(result.success() ? "notice" : "error", result.message());
    return "redirect:/dashboard#timeline";
  }

  static List<TimelineItem> timeline(
      List<DashboardViewService.TravelEntry> events,
      List<PlayerSocialService.PostView> posts) {
    return timeline(events, posts, List.of());
  }

  static List<TimelineItem> timeline(
      List<DashboardViewService.TravelEntry> events,
      List<PlayerSocialService.PostView> posts,
      List<AiCommentService.AiCommentEntry> aiComments) {
    List<TimelineItem> timeline = new ArrayList<>(events.size() + posts.size() + aiComments.size());
    sampledEvents(events).stream().map(TimelineItem::event).forEach(timeline::add);
    posts.stream().map(TimelineItem::post).forEach(timeline::add);
    aiComments.stream().map(TimelineItem::aiComment).forEach(timeline::add);
    timeline.sort(Comparator.comparing(
        TimelineItem::occurredAt,
        Comparator.nullsLast(Comparator.reverseOrder())));
    return curatedTimeline(timeline);
  }

  static TimelinePage timelinePage(
      List<DashboardViewService.TravelEntry> events,
      List<PlayerSocialService.PostView> posts,
      List<AiCommentService.AiCommentEntry> aiComments,
      int offset) {
    List<TimelineItem> all = timeline(events, posts, aiComments);
    int from = Math.max(0, Math.min(offset, all.size()));
    int to = Math.min(from + INITIAL_TIMELINE_ITEMS, all.size());
    return new TimelinePage(all.subList(from, to), to, to < all.size());
  }

  static TimelinePage timelinePage(TimelinePostService.FeedPage page) {
    return new TimelinePage(mergePresencePosts(page.posts().stream().map(TimelineItem::post).toList()),
        page.nextOffset(), page.hasMore());
  }

  static List<TimelineItem> mergePresencePosts(List<TimelineItem> items) {
    List<TimelineItem> merged = new ArrayList<>();
    for (int index = 0; index < items.size();) {
      TimelineItem first = items.get(index);
      if (!"LOGIN".equals(first.kind()) && !"LOGOUT".equals(first.kind())) {
        merged.add(first);
        index++;
        continue;
      }
      List<TimelineItem> group = new ArrayList<>();
      group.add(first);
      LocalDateTime firstAt = parseTimelineTime(first.occurredAt());
      int cursor = index + 1;
      while (cursor < items.size()) {
        TimelineItem candidate = items.get(cursor);
        LocalDateTime candidateAt = parseTimelineTime(candidate.occurredAt());
        if (!first.kind().equals(candidate.kind()) || firstAt == null || candidateAt == null
            || Math.abs(java.time.Duration.between(firstAt, candidateAt).toMinutes()) > EVENT_WINDOW_MINUTES) {
          break;
        }
        group.add(candidate);
        cursor++;
      }
      if (group.size() == 1) {
        merged.add(first);
      } else {
        String names = group.stream().map(TimelineItem::actor).distinct()
            .collect(java.util.stream.Collectors.joining("、"));
        String action = "LOGIN".equals(first.kind()) ? "荒野へログインしました。" : "荒野からログアウトしました。";
        merged.add(new TimelineItem("POST", first.postId(), null,
            TimelinePostType.LOGIN.systemActorName().orElse("CONNECTION MONITOR"), first.kind(),
            first.occurredAt(), names + " が" + action, "", first.tone(), first.tag(), "", "",
            first.reactions(), first.currentReaction(), false));
      }
      index = cursor;
    }
    return List.copyOf(merged);
  }

  /**
   * Makes the first viewport a live observation feed instead of letting old social posts occupy it
   * indefinitely. Everything remains available below it, with archived AI observations woven back
   * into the history at a restrained interval.
   */
  static List<TimelineItem> curatedTimeline(List<TimelineItem> sorted) {
    if (sorted.size() <= INITIAL_TIMELINE_ITEMS) {
      return List.copyOf(sorted);
    }
    LocalDateTime newest = sorted.stream()
        .map(item -> parseTimelineTime(item.occurredAt()))
        .filter(java.util.Objects::nonNull)
        .max(LocalDateTime::compareTo)
        .orElse(LocalDateTime.now());
    List<TimelineItem> head = new ArrayList<>();
    List<TimelineItem> history = new ArrayList<>();
    int recentPosts = 0;
    int recentAi = 0;
    for (TimelineItem item : sorted) {
      LocalDateTime occurredAt = parseTimelineTime(item.occurredAt());
      boolean recentPost = "POST".equals(item.itemType())
          && occurredAt != null
          && !occurredAt.isBefore(newest.minusMinutes(RECENT_POST_MINUTES));
      boolean include = head.size() < INITIAL_TIMELINE_ITEMS
          && ("EVENT".equals(item.itemType())
              || (recentPost && recentPosts < 3)
              || ("AI".equals(item.itemType()) && recentAi < 1));
      if (include) {
        head.add(item);
        recentPosts += "POST".equals(item.itemType()) ? 1 : 0;
        recentAi += "AI".equals(item.itemType()) ? 1 : 0;
      } else {
        history.add(item);
      }
    }
    head.sort(Comparator.comparing(
        TimelineItem::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
    List<TimelineItem> archivedAi = history.stream()
        .filter(item -> "AI".equals(item.itemType())).toList();
    List<TimelineItem> ordinaryHistory = new ArrayList<>(history.stream()
        .filter(item -> !"AI".equals(item.itemType())).toList());
    List<TimelineItem> result = new ArrayList<>(sorted.size());
    result.addAll(head);
    int aiIndex = 0;
    for (int index = 0; index < ordinaryHistory.size(); index++) {
      result.add(ordinaryHistory.get(index));
      if ((index + 1) % 12 == 0 && aiIndex < archivedAi.size()) {
        result.add(archivedAi.get(aiIndex++));
      }
    }
    result.addAll(archivedAi.subList(aiIndex, archivedAi.size()));
    return List.copyOf(result);
  }

  /**
   * Keeps the public feed readable when the game emits many events at once. Each five-minute
   * window contributes one stable pseudo-random event, while player-authored posts are never
   * sampled. Stability prevents the feed from changing merely because the page was refreshed.
   */
  static List<DashboardViewService.TravelEntry> sampledEvents(
      List<DashboardViewService.TravelEntry> events) {
    Map<LocalDateTime, DashboardViewService.TravelEntry> selected = new LinkedHashMap<>();
    List<DashboardViewService.TravelEntry> alwaysVisible = new ArrayList<>();
    for (DashboardViewService.TravelEntry event : events) {
      if (TimelineEventPolicy.isAlwaysVisible(event.kind())) {
        alwaysVisible.add(event);
        continue;
      }
      LocalDateTime occurredAt = parseTimelineTime(event.occurredAt());
      if (occurredAt == null) {
        selected.putIfAbsent(LocalDateTime.MIN.plusNanos(selected.size()), event);
        continue;
      }
      LocalDateTime window = occurredAt
          .withMinute((occurredAt.getMinute() / EVENT_WINDOW_MINUTES) * EVENT_WINDOW_MINUTES)
          .withSecond(0)
          .withNano(0);
      selected.merge(window, event, (current, candidate) ->
          eventSampleScore(window, candidate) > eventSampleScore(window, current)
              ? candidate : current);
    }
    List<DashboardViewService.TravelEntry> result = new ArrayList<>(
        selected.size() + alwaysVisible.size());
    result.addAll(selected.values());
    result.addAll(alwaysVisible);
    return List.copyOf(result);
  }

  private static LocalDateTime parseTimelineTime(String value) {
    try {
      return value == null ? null : LocalDateTime.parse(value, TIMELINE_TIME);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static int eventSampleScore(
      LocalDateTime window,
      DashboardViewService.TravelEntry event) {
    return java.util.Objects.hash(window, event.actor(), event.kind(), event.occurredAt());
  }

  @GetMapping("/players/{playerId}")
  public String player(
      @PathVariable Long playerId,
      Model model,
      Authentication authentication) {
    if (currentAccountService.current(authentication)
        .map(account -> "VIEWER".equals(account.getRole()))
        .orElse(false)) {
      return "redirect:/dashboard";
    }
    DashboardViewService.PlayerDetailView player = dashboardViewService.playerDetail(playerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    model.addAttribute("player", player);
    return "player-detail";
  }

  @GetMapping("/server")
  public String server(Model model) {
    model.addAttribute("server", dashboardViewService.serverDetail());
    return "server-detail";
  }

  @GetMapping("/kills")
  public String kills(Model model) {
    model.addAttribute("kills", dashboardViewService.killDetail());
    return "kill-detail";
  }

  @GetMapping("/vehicles")
  public String vehicles(Model model) {
    model.addAttribute("vehicles", dashboardViewService.vehicleDetail());
    return "vehicle-detail";
  }

  @GetMapping("/exploration")
  public String exploration(Model model) {
    model.addAttribute("exploration", dashboardViewService.explorationDetail());
    return "exploration-detail";
  }

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
    return "diary-generation-data";
  }

  @GetMapping("/maintenance/diaries/{date}/edit")
  public String diaryEditor(@PathVariable LocalDate date, Model model) {
    model.addAttribute("packet", diaryMaintenanceService.packet(date));
    model.addAttribute("editorEnabled", aiCommentService.editorEnabled());
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
      redirectAttributes.addFlashAttribute("error", "AI日記を生成できませんでした。ログを確認してください。");
    }
    return "redirect:/maintenance/diaries/" + date;
  }

  @PostMapping("/maintenance/diaries/{date}/edit")
  public String publishDiary(
      @PathVariable LocalDate date,
      @RequestParam String title,
      @RequestParam String body,
      @RequestParam(required = false, defaultValue = "") String editorKey,
      RedirectAttributes redirectAttributes) {
    try {
      aiCommentService.publish(date, title, body, editorKey);
      redirectAttributes.addFlashAttribute("notice", date + " の冒険日記を登録しました。");
      return "redirect:/maintenance/diaries/" + date;
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      redirectAttributes.addFlashAttribute("draftTitle", title);
      redirectAttributes.addFlashAttribute("draftBody", body);
      return "redirect:/maintenance/diaries/" + date + "/edit";
    }
  }

  public record TimelineItem(
      String itemType,
      Long postId,
      Long playerId,
      String actor,
      String kind,
      String occurredAt,
      String message,
      String coordinate,
      String tone,
      String tag,
      String linkUrl,
      String linkLabel,
      Map<ReactionType, Long> reactions,
      String currentReaction,
      boolean ownPost) {

    public long reactionCount() {
      return reactions == null ? 0 : reactions.values().stream().mapToLong(Long::longValue).sum();
    }

    public String avatarUrl() {
      return TimelinePostType.parse(kind).flatMap(TimelinePostType::avatarPath).orElse("");
    }

    public TimelineItem(
        String itemType, Long postId, Long playerId, String actor, String kind,
        String occurredAt, String message, String coordinate, String tone,
        Long likeCount, boolean likedByCurrentAccount, boolean ownPost) {
      this(itemType, postId, playerId, actor, kind, occurredAt, message, coordinate, tone,
          kind, "", "",
          likeCount == null ? Map.of() : Map.of(ReactionType.NICE, likeCount),
          likedByCurrentAccount ? ReactionType.NICE.name() : null, ownPost);
    }

    static TimelineItem event(DashboardViewService.TravelEntry event) {
      return new TimelineItem(
          "EVENT", null, null, event.actor(), event.kind(), event.occurredAt(),
          event.message(), event.coordinate(), event.tone(), event.kind(), "", "", Map.of(), null, false);
    }

    static TimelineItem post(PlayerSocialService.PostView post) {
      return new TimelineItem(
          "POST", post.id(), post.playerId(), post.playerName(), "つぶやき", post.createdAt(),
          post.body(), "", "community", "投稿", "", "", post.reactions(), post.currentReaction(), post.own());
    }

    static TimelineItem aiComment(AiCommentService.AiCommentEntry comment) {
      TimelinePostType type = TimelinePostType.fromAiPostType(comment.postType());
      return new TimelineItem(
          "AI", null, comment.targetPlayerId(),
          type.systemActorName().orElse("WATCHPOINT"),
          type.name(),
          DISPLAY_TIME_FORMATTER.format(comment.publishedAt()),
          comment.body(), "", type.tone(), type.tagLabel(), "", "", Map.of(), null, false);
    }

    static TimelineItem post(TimelinePostService.PostView post) {
      TimelinePostType type = TimelinePostType.parse(post.postType()).orElse(null);
      Long actorPlayerId = type == null || type.linksActorToPlayer() ? post.playerId() : null;
      return new TimelineItem(
          "POST", post.id(), actorPlayerId, post.actor(), post.postType(), post.occurredAt(), post.message(),
          post.coordinate(), type == null ? "neutral" : type.tone(),
          type == null ? "ACTIVITY" : type.tagLabel(),
          post.linkUrl(), post.linkLabel(), post.reactions(), post.currentReaction(), post.ownPost());
    }
  }

  public record TimelinePage(List<TimelineItem> items, int nextOffset, boolean hasMore) {
  }

}
