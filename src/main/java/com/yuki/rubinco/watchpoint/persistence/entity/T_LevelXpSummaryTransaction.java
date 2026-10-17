package com.yuki.rubinco.watchpoint.persistence.entity;

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
@Table(name = "T_LEVEL_XP_SUMMARY_TRANSACTION")
public class T_LevelXpSummaryTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "level_xp_summary_transaction_id")
  private Long id;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "player_name", columnDefinition = "TEXT")
  private String playerName;

  @Column(name = "player_entity_id")
  private Integer playerEntityId;

  @Column(name = "player_id")
  private Long playerId;

  @Column(name = "player_inference_method", length = 80)
  private String playerInferenceMethod;

  @Column(name = "xp_from_loot", nullable = false)
  private Integer xpFromLoot = 0;

  @Column(name = "xp_from_harvesting", nullable = false)
  private Integer xpFromHarvesting = 0;

  @Column(name = "xp_from_kill", nullable = false)
  private Integer xpFromKill = 0;

  @Column(name = "xp_total", nullable = false)
  private Integer xpTotal;

  @Column(name = "source_file", nullable = false, columnDefinition = "TEXT")
  private String sourceFile;

  @Column(name = "source_log_hash", nullable = false, unique = true, length = 64)
  private String sourceLogHash;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }
}
