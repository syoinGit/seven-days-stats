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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_TIMELINE_POST_REACTION")
public class T_TimelinePostReaction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "timeline_post_id", nullable = false)
  private Long timelinePostId;

  @Column(name = "account_id", nullable = false)
  private Long accountId;

  @Column(name = "reaction_type", nullable = false, length = 20)
  private String reactionType;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() { if (createdAt == null) createdAt = OffsetDateTime.now(); }
}
