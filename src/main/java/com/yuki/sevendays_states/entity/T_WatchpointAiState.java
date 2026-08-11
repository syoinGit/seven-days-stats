package com.yuki.sevendays_states.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "T_WATCHPOINT_AI_STATE")
@Getter
@Setter
@NoArgsConstructor
public class T_WatchpointAiState {
  @Id
  private Long id;

  @Column(name = "agent_id", nullable = false, unique = true)
  private Long agentId;

  @Column(name = "memory_summary", nullable = false, columnDefinition = "TEXT")
  private String memorySummary = "";

  @Column(name = "alertness", nullable = false)
  private int alertness;
  @Column(name = "curiosity", nullable = false)
  private int curiosity;
  @Column(name = "empathy", nullable = false)
  private int empathy;
  @Column(name = "tension", nullable = false)
  private int tension;
  @Column(name = "hope", nullable = false)
  private int hope;

  @Column(name = "last_observed_at")
  private OffsetDateTime lastObservedAt;
  @Column(name = "last_posted_at")
  private OffsetDateTime lastPostedAt;
  @Version
  @Column(name = "version", nullable = false)
  private long version;
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
