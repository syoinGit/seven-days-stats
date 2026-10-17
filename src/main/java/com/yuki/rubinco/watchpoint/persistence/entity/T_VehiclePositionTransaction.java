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
@Table(name = "T_VEHICLE_POSITION_TRANSACTION")
public class T_VehiclePositionTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "vehicle_position_transaction_id")
  private Long id;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "event_type", nullable = false, length = 40)
  private String eventType;

  @Column(name = "vehicle_entity_id", nullable = false)
  private Integer vehicleEntityId;

  @Column(name = "vehicle_type", nullable = false, columnDefinition = "TEXT")
  private String vehicleType;

  @Column(name = "vehicle_name", columnDefinition = "TEXT")
  private String vehicleName;

  @Column(name = "owner_player_id")
  private Long ownerPlayerId;

  @Column(name = "owner_cross_platform_id", columnDefinition = "TEXT")
  private String ownerCrossPlatformId;

  @Column(name = "owner_inference_method", length = 80)
  private String ownerInferenceMethod;

  @Column(name = "attributed_player_id")
  private Long attributedPlayerId;

  @Column(name = "attribution_method", length = 80)
  private String attributionMethod;

  @Column(name = "movement_valid", nullable = false)
  private boolean movementValid;

  @Column(name = "position_x")
  private Integer positionX;

  @Column(name = "position_y")
  private Integer positionY;

  @Column(name = "position_z")
  private Integer positionZ;

  @Column(name = "movement_distance", nullable = false)
  private BigDecimal movementDistance = BigDecimal.ZERO;

  @Column(name = "removal_reason", columnDefinition = "TEXT")
  private String removalReason;

  @Column(name = "source_file", nullable = false, columnDefinition = "TEXT")
  private String sourceFile;

  @Column(name = "source_log_hash", nullable = false, unique = true, length = 64)
  private String sourceLogHash;

  @Column(name = "raw_line", nullable = false, columnDefinition = "TEXT")
  private String rawLine;

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
