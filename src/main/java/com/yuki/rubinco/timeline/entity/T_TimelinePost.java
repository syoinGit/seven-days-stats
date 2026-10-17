package com.yuki.rubinco.timeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Shared game timeline read model. Watchpoint source logs remain their system of record. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_TIMELINE_POST")
public class T_TimelinePost {

  @Column(name = "game_id", nullable = false, length = 40)
  private String gameId = "7dtd";

  @Column(name = "event_id", unique = true, length = 100)
  private String eventId;

  @Column(name = "title", nullable = false, length = 160)
  private String title = "Adventure event";

  @Column(name = "progress")
  private Integer progress;

  @Column(name = "stage_name", length = 160)
  private String stageName;

  @Column(name = "creator_sub", length = 80)
  private String creatorSub;

  @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT")
  private String metadataJson = "{}";

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "timeline_post_id")
  private Long id;

  @Column(name = "published_at", nullable = false)
  private OffsetDateTime publishedAt;

  @Column(name = "post_type", nullable = false, length = 40)
  private String postType;

  @Column(name = "actor_type", nullable = false, length = 20)
  private String actorType;

  // Ownership belongs to the portal account even if its game-player link changes later.
  @Column(name = "author_account_id")
  private Long authorAccountId;

  @Column(name = "actor_player_id")
  private Long actorPlayerId;

  @Column(name = "actor_name", nullable = false, columnDefinition = "TEXT")
  private String actorName;

  @Column(name = "message", nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(name = "coordinate", length = 80)
  private String coordinate;

  @Column(name = "link_url", length = 300)
  private String linkUrl;

  @Column(name = "link_label", length = 80)
  private String linkLabel;

  @Column(name = "image_url", length = 1200)
  private String imageUrl;

  @Column(name = "base_like_count", nullable = false)
  private int baseLikeCount;

  @Column(name = "post_subtype", length = 40)
  private String postSubtype;

  @Column(name = "source_type", nullable = false, length = 40)
  private String sourceType;

  @Column(name = "source_id")
  private Long sourceId;

  @Column(name = "source_hash", nullable = false, unique = true, length = 100)
  private String sourceHash;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "visible", nullable = false)
  private boolean visible = true;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (publishedAt == null) publishedAt = OffsetDateTime.now();
    if (createdAt == null) createdAt = OffsetDateTime.now();
    if (updatedAt == null) updatedAt = createdAt;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
