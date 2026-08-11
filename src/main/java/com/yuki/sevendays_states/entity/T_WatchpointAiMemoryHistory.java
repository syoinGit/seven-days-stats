package com.yuki.sevendays_states.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "T_WATCHPOINT_AI_MEMORY_HISTORY")
@Getter
@Setter
@NoArgsConstructor
public class T_WatchpointAiMemoryHistory {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "state_id", nullable = false)
  private Long stateId;
  @Column(name = "source_comment_id")
  private Long sourceCommentId;
  @Column(name = "post_type", nullable = false, length = 30)
  private String postType;
  @Column(name = "memory_before", nullable = false, columnDefinition = "TEXT")
  private String memoryBefore;
  @Column(name = "memory_after", nullable = false, columnDefinition = "TEXT")
  private String memoryAfter;
  @Column(name = "emotions_before", nullable = false, length = 255)
  private String emotionsBefore;
  @Column(name = "emotions_after", nullable = false, length = 255)
  private String emotionsAfter;
  @Column(name = "change_reason", nullable = false, length = 500)
  private String changeReason;
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;
}
