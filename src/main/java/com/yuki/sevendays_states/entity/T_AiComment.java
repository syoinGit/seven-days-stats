package com.yuki.sevendays_states.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_AI_COMMENT")
public class T_AiComment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "ai_comment_id")
  private Long id;

  @Column(name = "title", nullable = false, length = 120)
  private String title;

  @Column(name = "body", nullable = false, columnDefinition = "TEXT")
  private String body;

  @Column(name = "diary_date")
  private LocalDate diaryDate;

  @Column(name = "published_at", nullable = false)
  private OffsetDateTime publishedAt;

  @Column(name = "source_type", nullable = false, length = 30)
  private String sourceType;

  @Column(name = "post_type", nullable = false, length = 30)
  private String postType = AiPostType.NORMAL.name();

  @Column(name = "target_player_id")
  private Long targetPlayerId;

  @Column(name = "ai_generated", nullable = false)
  private boolean aiGenerated;

  @Column(name = "summary", length = 500)
  private String summary;

  @Column(name = "tags", length = 500)
  private String tags;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (publishedAt == null) {
      publishedAt = OffsetDateTime.now();
    }
    if (sourceType == null) {
      sourceType = "MANUAL_BETA";
    }
    if (postType == null) {
      postType = AiPostType.NORMAL.name();
    }
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }
}
