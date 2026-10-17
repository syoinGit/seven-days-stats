package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_PLAYER_POSITION_TRANSACTION")
public class T_PlayerPositionTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "player_position_transaction_id")
  private Long id;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "player_name", nullable = false, columnDefinition = "TEXT")
  private String playerName;

  @Column(name = "player_entity_id", nullable = false)
  private Integer playerEntityId;

  @Column(name = "player_id")
  private Long playerId;

  @Column(name = "position_x", nullable = false)
  private Integer positionX;

  @Column(name = "position_y")
  private Integer positionY;

  @Column(name = "position_z", nullable = false)
  private Integer positionZ;

  @Column(name = "position_source_type", nullable = false, length = 80)
  private String positionSourceType;

  @Column(name = "inference_method", length = 80)
  private String inferenceMethod;

  @Column(name = "movement_distance", nullable = false)
  private BigDecimal movementDistance = BigDecimal.ZERO;

  @Column(name = "movement_mode", nullable = false, length = 20)
  private String movementMode = "UNKNOWN";

  @Column(name = "vehicle_entity_id")
  private Integer vehicleEntityId;

  @Column(name = "movement_inference_method", length = 80)
  private String movementInferenceMethod;

  @Column(name = "source_event_hash", nullable = false, unique = true, length = 64)
  private String sourceEventHash;

  @Column(name = "source_file", nullable = false, columnDefinition = "TEXT")
  private String sourceFile;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
    if (movementDistance == null) {
      movementDistance = BigDecimal.ZERO;
    }
  }
}
