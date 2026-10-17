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
@Table(name = "T_SLEEPER_TRANSACTION")
public class T_SleeperTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "sleeper_transaction_id")
  private Long id;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "transaction_type", nullable = false, length = 30)
  private String transactionType;

  @Column(name = "sleeper_volume_x", nullable = false)
  private Integer sleeperVolumeX;

  @Column(name = "sleeper_volume_y", nullable = false)
  private Integer sleeperVolumeY;

  @Column(name = "sleeper_volume_z", nullable = false)
  private Integer sleeperVolumeZ;

  @Column(name = "position_x", nullable = false)
  private Integer positionX;

  @Column(name = "position_y", nullable = false)
  private Integer positionY;

  @Column(name = "position_z", nullable = false)
  private Integer positionZ;

  @Column(name = "chunk_x")
  private Integer chunkX;

  @Column(name = "chunk_z")
  private Integer chunkZ;

  @Column(name = "sleeper_group", columnDefinition = "TEXT")
  private String sleeperGroup;

  @Column(name = "entity_class", nullable = false, columnDefinition = "TEXT")
  private String entityClass;

  @Column(name = "entity_count")
  private Integer entityCount;

  @Column(name = "player_name", columnDefinition = "TEXT")
  private String playerName;

  @Column(name = "player_entity_id")
  private Integer playerEntityId;

  @Column(name = "player_id")
  private Long playerId;

  @Column(name = "player_inference_method", length = 80)
  private String playerInferenceMethod;

  @Column(name = "player_position_x")
  private Integer playerPositionX;

  @Column(name = "player_position_y")
  private Integer playerPositionY;

  @Column(name = "player_position_z")
  private Integer playerPositionZ;

  @Column(name = "player_current_state_updated_at")
  private OffsetDateTime playerCurrentStateUpdatedAt;

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
