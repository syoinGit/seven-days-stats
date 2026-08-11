package com.yuki.sevendays_states.entity;

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

@Entity
@Table(name = "M_AI_AGENT")
@Getter
@Setter
@NoArgsConstructor
public class M_AiAgent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "agent_code", nullable = false, unique = true, length = 30)
  private String agentCode;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(name = "personality_prompt", nullable = false, columnDefinition = "TEXT")
  private String personalityPrompt;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    createdAt = createdAt == null ? now : createdAt;
    updatedAt = updatedAt == null ? now : updatedAt;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
