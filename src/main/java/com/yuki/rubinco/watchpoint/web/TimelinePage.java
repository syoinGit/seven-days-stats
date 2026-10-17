package com.yuki.rubinco.watchpoint.web;

import com.yuki.rubinco.timeline.entity.ReactionType;
import com.yuki.rubinco.timeline.entity.TimelinePostType;
import com.yuki.rubinco.timeline.service.TimelinePostService;
import java.util.List;
import java.util.Map;

/** Presentation of the persisted feed shared by the initial page and older-page fragments. */
public record TimelinePage(List<Item> items, int nextOffset, boolean hasMore) {

  static TimelinePage from(TimelinePostService.FeedPage page) {
    return new TimelinePage(page.posts().stream().map(Item::from).toList(),
        page.nextOffset(), page.hasMore());
  }

  public record Item(
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
      boolean ownPost,
      String imageUrl,
      int baseLikeCount,
      String postSubtype) {

    public long reactionCount() {
      return reactions == null ? 0 : reactions.values().stream().mapToLong(Long::longValue).sum();
    }

    public long displayLikeCount() {
      long userLikes = reactions == null ? 0 : reactions.getOrDefault(ReactionType.NICE, 0L);
      return Math.max(0, baseLikeCount) + userLikes;
    }

    public String avatarUrl() {
      return TimelinePostType.parse(kind).flatMap(TimelinePostType::avatarPath).orElse("");
    }

    static Item from(TimelinePostService.PostView post) {
      TimelinePostType type = TimelinePostType.parse(post.postType()).orElse(null);
      Long actorPlayerId = type == null || type.linksActorToPlayer() ? post.playerId() : null;
      return new Item(
          "POST", post.id(), actorPlayerId, post.actor(), post.postType(), post.occurredAt(), post.message(),
          post.coordinate(), type == null ? "neutral" : type.tone(),
          type == null ? "ACTIVITY" : type.tagLabel(),
          post.linkUrl(), post.linkLabel(), post.reactions(), post.currentReaction(), post.ownPost(),
          post.imageUrl(), post.baseLikeCount(), post.postSubtype());
    }
  }

}
