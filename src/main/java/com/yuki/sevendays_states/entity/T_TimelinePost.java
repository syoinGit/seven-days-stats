package com.yuki.sevendays_states.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Read model for the SNS feed. Source logs remain the system of record. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_TIMELINE_POST")
public class T_TimelinePost {

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

  @Column(name = "actor_player_id")
  private Long actorPlayerId;

  @Column(name = "actor_name", nullable = false, columnDefinition = "TEXT")
  private String actorName;

  @Column(name = "message", nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(name = "coordinate", length = 80)
  private String coordinate;

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
  }
}
