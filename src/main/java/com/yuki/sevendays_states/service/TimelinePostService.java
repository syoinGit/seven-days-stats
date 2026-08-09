package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.AiPostType;
import com.yuki.sevendays_states.entity.M_Player;
import com.yuki.sevendays_states.entity.M_WebAccount;
import com.yuki.sevendays_states.entity.ReactionType;
import com.yuki.sevendays_states.entity.T_TimelinePost;
import com.yuki.sevendays_states.entity.T_TimelinePostReaction;
import com.yuki.sevendays_states.entity.TimelinePostType;
import com.yuki.sevendays_states.repository.M_PlayerRepository;
import com.yuki.sevendays_states.repository.T_TimelinePostReactionRepository;
import com.yuki.sevendays_states.repository.T_TimelinePostRepository;
import com.yuki.sevendays_states.util.DisplayTimeFormatter;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
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

  public static final int PAGE_SIZE = 12;

  private final T_TimelinePostRepository postRepository;
  private final T_TimelinePostReactionRepository reactionRepository;
  private final M_PlayerRepository playerRepository;
  private final CurrentWebAccountService currentAccountService;
  private final TimelineMessageFactory messageFactory;
  private final DisplayTimeFormatter displayTimeFormatter = new DisplayTimeFormatter();

  @Transactional(readOnly = true)
  public FeedPage feed(Authentication authentication, int offset) {
    int safeOffset = Math.max(0, Math.min(offset, 600));
    int pageNumber = safeOffset / PAGE_SIZE;
    var page = postRepository.findAllByVisibleTrueOrderByPublishedAtDescIdDesc(
        PageRequest.of(pageNumber, PAGE_SIZE));
    M_WebAccount current = currentAccountService.current(authentication).orElse(null);
    List<PostView> posts = page.getContent().stream().map(post -> toView(post, current)).toList();
    return new FeedPage(posts, safeOffset + posts.size(), page.hasNext());
  }

  @Transactional
  public PlayerSocialService.ActionResult createPlayerPost(Authentication authentication, String rawBody) {
    Optional<M_WebAccount> account = currentAccountService.current(authentication);
    if (account.isEmpty()) return PlayerSocialService.ActionResult.failure("投稿するにはログインしてください。");
    if (account.get().getPlayerId() == null) {
      return PlayerSocialService.ActionResult.failure("ゲームプレイヤーに紐付いたアカウントで投稿してください。");
    }
    String body = rawBody == null ? "" : rawBody.strip();
    if (body.isBlank()) return PlayerSocialService.ActionResult.failure("投稿内容を入力してください。");
    if (body.length() > 1000) return PlayerSocialService.ActionResult.failure("投稿は1000文字以内で入力してください。");
    M_Player player = playerRepository.findById(account.get().getPlayerId()).orElse(null);
    if (player == null) return PlayerSocialService.ActionResult.failure("紐付いたゲームプレイヤーが見つかりません。");
    OffsetDateTime now = OffsetDateTime.now();
    save(TimelinePostType.PLAYER_MESSAGE, "PLAYER", player.getId(), player.getPlayerName(), body, "",
        "", "", "PLAYER_POST", null, "PLAYER_POST:" + account.get().getId() + ":" + now.toInstant(), 100, now);
    return PlayerSocialService.ActionResult.success("投稿しました。");
  }

  @Transactional
  public PlayerSocialService.LikeResult toggleReaction(
      Authentication authentication, Long postId, String rawReaction) {
    M_WebAccount account = currentAccountService.current(authentication).orElse(null);
    if (account == null) return PlayerSocialService.LikeResult.failure("リアクションするにはログインしてください。");
    if (postId == null || postRepository.findById(postId).isEmpty()) {
      return PlayerSocialService.LikeResult.failure("投稿が見つかりません。");
    }
    ReactionType reaction;
    try {
      reaction = ReactionType.valueOf(rawReaction == null ? "" : rawReaction.strip().toUpperCase());
    } catch (IllegalArgumentException exception) {
      return PlayerSocialService.LikeResult.failure("利用できないリアクションです。");
    }
    Optional<T_TimelinePostReaction> existing = reactionRepository
        .findByTimelinePostIdAndAccountId(postId, account.getId());
    if (existing.isPresent()) {
      if (reaction.name().equals(existing.get().getReactionType())) {
        reactionRepository.delete(existing.get());
        return PlayerSocialService.LikeResult.success("リアクションを取り消しました。", false,
            reactionRepository.findAllByTimelinePostId(postId).size(), null);
      }
      existing.get().setReactionType(reaction.name());
      reactionRepository.save(existing.get());
      return PlayerSocialService.LikeResult.success("リアクションを変更しました。", true,
          reactionRepository.findAllByTimelinePostId(postId).size(), reaction.name());
    }
    T_TimelinePostReaction created = new T_TimelinePostReaction();
    created.setTimelinePostId(postId);
    created.setAccountId(account.getId());
    created.setReactionType(reaction.name());
    reactionRepository.save(created);
    return PlayerSocialService.LikeResult.success("リアクションしました。", true,
        reactionRepository.findAllByTimelinePostId(postId).size(), reaction.name());
  }

  @Transactional
  public PlayerSocialService.ActionResult deletePlayerPost(Authentication authentication, Long postId) {
    M_WebAccount account = currentAccountService.current(authentication).orElse(null);
    if (account == null) return PlayerSocialService.ActionResult.failure("投稿を削除するにはログインしてください。");
    T_TimelinePost post = postId == null ? null : postRepository.findById(postId).orElse(null);
    if (post == null || !"PLAYER_MESSAGE".equals(post.getPostType())) {
      return PlayerSocialService.ActionResult.failure("投稿が見つかりません。");
    }
    if (!account.getPlayerId().equals(post.getActorPlayerId())) {
      return PlayerSocialService.ActionResult.failure("自分の投稿だけ削除できます。");
    }
    reactionRepository.deleteAllByTimelinePostId(postId);
    postRepository.delete(post);
    return PlayerSocialService.ActionResult.success("投稿を削除しました。");
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
          ? postRepository.existsByPostTypeAndPublishedAtAfter(type.name(), after)
          : postRepository.existsByActorPlayerIdAndPostTypeAndPublishedAtAfter(
              playerId, type.name(), after);
      if (coolingDown) return;
    }
    String actorName = type == TimelinePostType.BLOOD_MOON ? "緊急警報" : displayName(playerName);
    save(type, "GAME", playerId, actorName, messageFactory.message(type, playerName, detail, sourceHash),
        coordinate, "", "", sourceType, sourceId, sourceHash, type.publishChance(), occurredAt);
  }

  @Transactional
  public void publishWatchpoint(Long commentId, Long targetPlayerId, OffsetDateTime publishedAt,
      String body, AiPostType aiPostType) {
    if (commentId == null) return;
    String sourceHash = "AI_COMMENT:" + commentId;
    if (!postRepository.existsBySourceHash(sourceHash)) {
      String actor = aiPostType == AiPostType.NORMAL ? "WATCHPOINT" : "観測分析局";
      TimelinePostType timelineType = TimelinePostType.fromAiPostType(aiPostType);
      save(timelineType, "WATCHPOINT", targetPlayerId, actor, body, "", "", "",
          "AI_COMMENT", commentId, sourceHash, 100, publishedAt);
    }
  }

  @Transactional
  public void publishDiary(Long commentId, LocalDate diaryDate, OffsetDateTime publishedAt,
      String title) {
    if (commentId == null || diaryDate == null) return;
    String sourceHash = "DIARY:" + commentId + ":" + publishedAt.toInstant();
    save(TimelinePostType.DIARY, "ARCHIVE", null, "冒険記録局",
        diaryDate + " の冒険日記「" + displayName(title) + "」を記録しました。",
        "", "/diaries/" + diaryDate, "日記を読む", "AI_DIARY", commentId, sourceHash, 100,
        publishedAt);
  }

  private boolean selected(TimelinePostType type, String sourceHash) {
    return type.isImmediate() || Math.floorMod(sourceHash.hashCode(), 100) < type.publishChance();
  }

  private void save(TimelinePostType type, String actorType, Long actorPlayerId, String actorName,
      String message, String coordinate, String linkUrl, String linkLabel,
      String sourceType, Long sourceId, String sourceHash,
      int priority, OffsetDateTime publishedAt) {
    T_TimelinePost post = new T_TimelinePost();
    post.setPostType(type.name());
    post.setActorType(actorType);
    post.setActorPlayerId(actorPlayerId);
    post.setActorName(displayName(actorName));
    post.setMessage(message);
    post.setCoordinate(coordinate == null ? "" : coordinate);
    post.setLinkUrl(linkUrl == null ? "" : linkUrl);
    post.setLinkLabel(linkLabel == null ? "" : linkLabel);
    post.setSourceType(sourceType);
    post.setSourceId(sourceId);
    post.setSourceHash(sourceHash);
    post.setPriority(priority);
    post.setPublishedAt(publishedAt == null ? OffsetDateTime.now() : publishedAt);
    postRepository.save(post);
  }

  private PostView toView(T_TimelinePost post, M_WebAccount current) {
    Map<ReactionType, Long> reactions = new EnumMap<>(ReactionType.class);
    for (ReactionType type : ReactionType.values()) reactions.put(type, 0L);
    reactionRepository.findAllByTimelinePostId(post.getId()).forEach(reaction -> {
      try {
        ReactionType type = ReactionType.valueOf(reaction.getReactionType());
        reactions.put(type, reactions.get(type) + 1);
      } catch (IllegalArgumentException ignored) { }
    });
    String currentReaction = current == null ? null : reactionRepository
        .findByTimelinePostIdAndAccountId(post.getId(), current.getId())
        .map(T_TimelinePostReaction::getReactionType).orElse(null);
    return new PostView(post.getId(), post.getActorPlayerId(), post.getActorName(), displayMessage(post),
        displayTimeFormatter.format(post.getPublishedAt()), post.getCoordinate(), post.getPostType(),
        post.getLinkUrl(), post.getLinkLabel(),
        reactions, currentReaction,
        current != null && current.getPlayerId() != null && current.getPlayerId().equals(post.getActorPlayerId())
            && "PLAYER_MESSAGE".equals(post.getPostType()));
  }

  private String displayName(String name) { return name == null || name.isBlank() ? "誰か" : name; }

  private String displayMessage(T_TimelinePost post) {
    String message = post.getMessage() == null ? "" : post.getMessage().strip();
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
                         Map<ReactionType, Long> reactions,
                         String currentReaction, boolean ownPost) { }
}
