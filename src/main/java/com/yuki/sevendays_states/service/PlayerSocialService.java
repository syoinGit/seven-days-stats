package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.ReactionType;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Compatibility facade for the player compose/reaction endpoints. */
@Service
@RequiredArgsConstructor
public class PlayerSocialService {
  private final TimelinePostService timelinePostService;

  public List<PostView> feed(Authentication authentication) {
    return timelinePostService.feed(authentication, 0).posts().stream()
        .filter(post -> "PLAYER_MESSAGE".equals(post.postType()))
        .map(post -> new PostView(post.id(), post.playerId(), post.actor(), post.message(), post.occurredAt(),
            post.reactions(), post.currentReaction(), post.ownPost()))
        .toList();
  }

  public ActionResult createPost(Authentication authentication, String body) {
    return timelinePostService.createPlayerPost(authentication, body);
  }

  public LikeResult toggleLike(Authentication authentication, Long postId) {
    return timelinePostService.toggleReaction(authentication, postId, ReactionType.NICE.name());
  }

  public LikeResult toggleReaction(Authentication authentication, Long postId, String reaction) {
    return timelinePostService.toggleReaction(authentication, postId, reaction);
  }

  public ActionResult deletePost(Authentication authentication, Long postId) {
    return timelinePostService.deletePlayerPost(authentication, postId);
  }

  public record PostView(Long id, Long playerId, String playerName, String body, String createdAt,
                         Map<ReactionType, Long> reactions, String currentReaction, boolean own) {
    public PostView(Long id, Long playerId, String playerName, String body, String createdAt,
                    long likeCount, boolean likedByCurrentAccount, boolean own) {
      this(id, playerId, playerName, body, createdAt, Map.of(ReactionType.NICE, likeCount),
          likedByCurrentAccount ? ReactionType.NICE.name() : null, own);
    }
    public long reactionCount() { return reactions.values().stream().mapToLong(Long::longValue).sum(); }
    public long likeCount() { return reactionCount(); }
    public boolean likedByCurrentAccount() { return currentReaction != null; }
  }

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
