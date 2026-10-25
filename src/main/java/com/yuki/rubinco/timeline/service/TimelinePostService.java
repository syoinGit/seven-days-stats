package com.yuki.rubinco.timeline.service;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.service.CurrentWebAccountService;
import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.timeline.entity.ReactionType;
import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import com.yuki.rubinco.timeline.entity.T_TimelinePostReaction;
import com.yuki.rubinco.timeline.entity.TimelinePostType;
import com.yuki.rubinco.timeline.repository.T_TimelinePostReactionRepository;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.watchpoint.ai.entity.AiPostType;
import com.yuki.rubinco.watchpoint.persistence.entity.M_Player;
import com.yuki.rubinco.watchpoint.persistence.repository.M_PlayerRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The feed's write/read model. It deliberately stores rendered game activity separately from raw
 * log transactions, so a dashboard request never has to reconstruct a social post from log rows.
 */
@Service
@RequiredArgsConstructor
public class TimelinePostService {

  private static final String WATCHPOINT_GAME_ID = "7dtd";
  public static final int PAGE_SIZE = 12;
  public static final int MAX_POST_CHARACTERS = 100;

  private final T_TimelinePostRepository postRepository;
  private final T_TimelinePostReactionRepository reactionRepository;
  private final M_PlayerRepository playerRepository;
  private final CurrentWebAccountService currentAccountService;
  private final TimelineMessageFactory messageFactory;
  private final DisplayTimeFormatter displayTimeFormatter = new DisplayTimeFormatter();

  @Transactional(readOnly = true)
  public FeedPage feed(Authentication authentication, int offset) {
    return feed(authentication, WATCHPOINT_GAME_ID, offset);
  }

  @Transactional(readOnly = true)
  public FeedPage feed(Authentication authentication, String gameId, int offset) {
    int safeOffset = Math.max(0, offset);
    int pageNumber = safeOffset / PAGE_SIZE;
    var page = postRepository.findVisibleFeed(gameId,
        PageRequest.of(pageNumber, PAGE_SIZE));
    M_WebAccount current = currentAccountService.current(authentication).orElse(null);
    List<Long> postIds = page.getContent().stream().map(T_TimelinePost::getId).toList();
    Map<Long, List<T_TimelinePostReaction>> reactions = postIds.isEmpty() ? Map.of()
        : reactionRepository.findAllByTimelinePostIdIn(postIds).stream()
            .collect(Collectors.groupingBy(T_TimelinePostReaction::getTimelinePostId));
    List<PostView> posts = page.getContent().stream()
        .map(post -> toView(post, current, reactions.getOrDefault(post.getId(), List.of())))
        .toList();
    long nextOffset = (long) pageNumber * PAGE_SIZE + posts.size();
    return new FeedPage(posts, (int) Math.min(nextOffset, Integer.MAX_VALUE),
        page.hasNext() && nextOffset < Integer.MAX_VALUE);
  }

  @Transactional
  public ActionResult createPlayerPost(Authentication authentication, String rawBody) {
    Optional<M_WebAccount> account = currentMember(authentication);
    if (account.isEmpty()) return ActionResult.failure("投稿するにはログインしてください。");
    if (account.get().getPlayerId() == null) {
      return ActionResult.failure("ゲームプレイヤーに紐付いたアカウントで投稿してください。");
    }
    String body = rawBody == null ? "" : rawBody.strip();
    if (body.isBlank()) return ActionResult.failure("投稿内容を入力してください。");
    if (body.codePointCount(0, body.length()) > MAX_POST_CHARACTERS) {
      return ActionResult.failure("投稿は100文字以内で入力してください。");
    }
    M_Player player = playerRepository.findById(account.get().getPlayerId()).orElse(null);
    if (player == null) return ActionResult.failure("紐付いたゲームプレイヤーが見つかりません。");
    OffsetDateTime now = OffsetDateTime.now();
    T_TimelinePost post = newPost(TimelinePostType.PLAYER_MESSAGE, "PLAYER", player.getId(), player.getPlayerName(), body, "",
        "", "", "PLAYER_POST", null, "PLAYER_POST:" + account.get().getId() + ":" + now.toInstant(), 100, now, "", 0, "");
    post.setAuthorAccountId(account.get().getId());
    postRepository.save(post);
    return ActionResult.success("投稿しました。");
  }

  @Transactional
  public LikeResult toggleReaction(
      Authentication authentication, Long postId, String rawReaction) {
    M_WebAccount account = currentMember(authentication).orElse(null);
    if (account == null) return LikeResult.failure("リアクションするにはログインしてください。");
    T_TimelinePost post = postId == null ? null : postRepository.findByIdForUpdate(postId).orElse(null);
    if (post == null || !WATCHPOINT_GAME_ID.equals(post.getGameId()) || !post.isVisible()) {
      return LikeResult.failure("投稿が見つかりません。");
    }
    ReactionType reaction;
    try {
      reaction = ReactionType.valueOf(rawReaction == null ? "" : rawReaction.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return LikeResult.failure("利用できないリアクションです。");
    }
    Optional<T_TimelinePostReaction> existing = reactionRepository
        .findByTimelinePostIdAndAccountId(postId, account.getId());
    if (existing.isPresent()) {
      if (reaction.name().equals(existing.get().getReactionType())) {
        reactionRepository.delete(existing.get());
        return LikeResult.success("リアクションを取り消しました。", false,
            reactionRepository.countByTimelinePostId(postId), null);
      }
      existing.get().setReactionType(reaction.name());
      reactionRepository.save(existing.get());
      return LikeResult.success("リアクションを変更しました。", true,
          reactionRepository.countByTimelinePostId(postId), reaction.name());
    }
    T_TimelinePostReaction created = new T_TimelinePostReaction();
    created.setTimelinePostId(postId);
    created.setAccountId(account.getId());
    created.setReactionType(reaction.name());
    reactionRepository.save(created);
    return LikeResult.success("リアクションしました。", true,
        reactionRepository.countByTimelinePostId(postId), reaction.name());
  }

  @Transactional
  public ActionResult deletePlayerPost(Authentication authentication, Long postId) {
    M_WebAccount account = currentMember(authentication).orElse(null);
    if (account == null) return ActionResult.failure("投稿を削除するにはログインしてください。");
    T_TimelinePost post = postId == null ? null : postRepository.findByIdForUpdate(postId).orElse(null);
    if (post == null || !WATCHPOINT_GAME_ID.equals(post.getGameId())
        || !"PLAYER_MESSAGE".equals(post.getPostType())) {
      return ActionResult.failure("投稿が見つかりません。");
    }
    if (!account.getId().equals(post.getAuthorAccountId())) {
      return ActionResult.failure("自分の投稿だけ削除できます。");
    }
    reactionRepository.deleteAllByTimelinePostId(postId);
    postRepository.delete(post);
    return ActionResult.success("投稿を削除しました。");
  }

  /** Publishes a game fact only if this event type's deterministic weighted selection accepts it. */
  @Transactional
  public void publishGameEvent(
      TimelinePostType type, Long playerId, String playerName, OffsetDateTime occurredAt,
      String detail, String coordinate, String sourceType, Long sourceId, String sourceHash) {
    if (sourceHash == null || postRepository.existsBySourceHash(sourceHash)) return;
    if (!selected(type, sourceHash)) return;
    if (type.cooldownMinutes() > 0) {
      OffsetDateTime after = occurredAt.minusMinutes(type.cooldownMinutes());
      boolean coolingDown = playerId == null
          ? postRepository.existsByGameIdAndPostTypeAndPublishedAtAfter(
              WATCHPOINT_GAME_ID, type.name(), after)
          : postRepository.existsByGameIdAndActorPlayerIdAndPostTypeAndPublishedAtAfter(
              WATCHPOINT_GAME_ID, playerId, type.name(), after);
      if (coolingDown) return;
    }
    String actorName = type.systemActorName().orElseGet(() -> displayName(playerName));
    Long actorPlayerId = type.linksActorToPlayer() ? playerId : null;
    save(type, "GAME", actorPlayerId, actorName,
        messageFactory.message(type, playerName, detail, sourceHash),
        coordinate, "", "", sourceType, sourceId, sourceHash, type.publishChance(), occurredAt);
  }

  @Transactional
  public void publishWatchpoint(Long commentId, Long targetPlayerId, OffsetDateTime publishedAt,
      String body, AiPostType aiPostType) {
    if (commentId == null) return;
    String sourceHash = "AI_COMMENT:" + commentId;
    if (!postRepository.existsBySourceHash(sourceHash)) {
      TimelinePostType timelineType = TimelinePostType.fromAiPostType(aiPostType);
      String actor = timelineType.systemActorName().orElse("WATCHPOINT");
      save(timelineType, "WATCHPOINT", targetPlayerId, actor, body, "", "", "",
          "AI_COMMENT", commentId, sourceHash, 100, publishedAt);
    }
  }

  @Transactional
  public void publishDiary(Long commentId, LocalDate diaryDate, OffsetDateTime publishedAt,
      String title) {
    if (commentId == null || diaryDate == null) return;
    String sourceHash = "DIARY:" + commentId + ":" + publishedAt.toInstant();
    save(TimelinePostType.DIARY, "ARCHIVE", null,
        TimelinePostType.DIARY.systemActorName().orElse("冒険記録局"),
        diaryDate + " の冒険日記「" + displayName(title) + "」を記録しました。",
        "", "/diaries/" + diaryDate, "日記を読む", "AI_DIARY", commentId, sourceHash, 100,
        publishedAt);
  }

  @Transactional(readOnly = true)
  public boolean existsBySourceHash(String sourceHash) {
    return sourceHash != null && postRepository.existsBySourceHash(sourceHash);
  }

  private boolean selected(TimelinePostType type, String sourceHash) {
    return type.isImmediate() || Math.floorMod(sourceHash.hashCode(), 100) < type.publishChance();
  }

  private void save(TimelinePostType type, String actorType, Long actorPlayerId, String actorName,
      String message, String coordinate, String linkUrl, String linkLabel,
      String sourceType, Long sourceId, String sourceHash,
      int priority, OffsetDateTime publishedAt) {
    postRepository.save(newPost(type, actorType, actorPlayerId, actorName, message, coordinate, linkUrl, linkLabel,
        sourceType, sourceId, sourceHash, priority, publishedAt, "", 0, ""));
  }

  private T_TimelinePost newPost(TimelinePostType type, String actorType, Long actorPlayerId, String actorName,
      String message, String coordinate, String linkUrl, String linkLabel,
      String sourceType, Long sourceId, String sourceHash,
      int priority, OffsetDateTime publishedAt, String imageUrl, int baseLikeCount,
      String postSubtype) {
    T_TimelinePost post = new T_TimelinePost();
    post.setPostType(type.name());
    post.setGameId(WATCHPOINT_GAME_ID);
    post.setEventId(sourceHash);
    post.setTitle(type.systemActorName().orElse(type.name()));
    post.setMetadataJson("{}");
    post.setActorType(actorType);
    post.setActorPlayerId(actorPlayerId);
    post.setActorName(displayName(actorName));
    post.setMessage(limitMessage(message));
    post.setCoordinate(coordinate == null ? "" : coordinate);
    post.setLinkUrl(linkUrl == null ? "" : linkUrl);
    post.setLinkLabel(linkLabel == null ? "" : linkLabel);
    post.setImageUrl(imageUrl == null || imageUrl.isBlank() ? null : imageUrl.strip());
    post.setBaseLikeCount(Math.max(0, baseLikeCount));
    post.setPostSubtype(postSubtype == null || postSubtype.isBlank() ? null : postSubtype.strip());
    post.setSourceType(sourceType);
    post.setSourceId(sourceId);
    post.setSourceHash(sourceHash);
    post.setPriority(priority);
    post.setPublishedAt(publishedAt == null ? OffsetDateTime.now() : publishedAt);
    return post;
  }

  private Optional<M_WebAccount> currentMember(Authentication authentication) {
    return currentAccountService.current(authentication)
        .filter(account -> "PLAYER".equals(account.getRole()) || "ADMIN".equals(account.getRole()));
  }

  private PostView toView(
      T_TimelinePost post, M_WebAccount current, List<T_TimelinePostReaction> postReactions) {
    Map<ReactionType, Long> reactions = new EnumMap<>(ReactionType.class);
    for (ReactionType type : ReactionType.values()) reactions.put(type, 0L);
    postReactions.forEach(reaction -> {
      try {
        ReactionType type = ReactionType.valueOf(reaction.getReactionType());
        reactions.put(type, reactions.get(type) + 1);
      } catch (IllegalArgumentException ignored) { }
    });
    String currentReaction = current == null ? null : postReactions.stream()
        .filter(reaction -> current.getId().equals(reaction.getAccountId()))
        .map(T_TimelinePostReaction::getReactionType).findFirst().orElse(null);
    return new PostView(post.getId(), post.getActorPlayerId(), post.getActorName(), displayMessage(post),
        displayTimeFormatter.format(post.getPublishedAt()), post.getCoordinate(), post.getPostType(),
        post.getLinkUrl(), post.getLinkLabel(), post.getImageUrl(), post.getBaseLikeCount(), post.getPostSubtype(),
        reactions, currentReaction,
        current != null && current.getId().equals(post.getAuthorAccountId())
            && "PLAYER_MESSAGE".equals(post.getPostType()));
  }

  private String displayName(String name) { return name == null || name.isBlank() ? "誰か" : name; }

  private String limitMessage(String message) {
    String normalized = message == null ? "" : message.strip();
    if (normalized.codePointCount(0, normalized.length()) <= MAX_POST_CHARACTERS) {
      return normalized;
    }
    int end = normalized.offsetByCodePoints(0, MAX_POST_CHARACTERS - 1);
    return normalized.substring(0, end).stripTrailing() + "…";
  }

  private String displayMessage(T_TimelinePost post) {
    String message = limitMessage(post.getMessage());
    if (!TimelinePostType.parse(post.getPostType())
        .map(TimelinePostType::isAiGenerated).orElse(false) || message.contains("\n")) return message;
    String sentenceBreaks = message.replaceAll("。(?=\\S)", "。\n");
    if (!sentenceBreaks.contains("\n") && sentenceBreaks.length() > 55) {
      int comma = sentenceBreaks.indexOf('、', 28);
      if (comma > 0 && comma < sentenceBreaks.length() - 1) {
        return sentenceBreaks.substring(0, comma + 1) + "\n" + sentenceBreaks.substring(comma + 1);
      }
    }
    return sentenceBreaks;
  }

  public record FeedPage(List<PostView> posts, int nextOffset, boolean hasMore) { }

  public record PostView(Long id, Long playerId, String actor, String message, String occurredAt,
                         String coordinate, String postType, String linkUrl, String linkLabel,
                         String imageUrl, int baseLikeCount, String postSubtype,
                         Map<ReactionType, Long> reactions,
                         String currentReaction, boolean ownPost) { }
  public record ActionResult(boolean success, String message) {
    public static ActionResult success(String message) { return new ActionResult(true, message); }
    public static ActionResult failure(String message) { return new ActionResult(false, message); }
  }

  public record LikeResult(boolean success, String message, boolean liked, long likeCount,
                           String reaction) {
    public static LikeResult success(String message, boolean liked, long likeCount, String reaction) {
      return new LikeResult(true, message, liked, likeCount, reaction);
    }
    public static LikeResult failure(String message) { return new LikeResult(false, message, false, 0, null); }
  }
}
