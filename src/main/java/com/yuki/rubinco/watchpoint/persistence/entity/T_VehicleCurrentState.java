package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "T_VEHICLE_CURRENT_STATE")
public class T_VehicleCurrentState {

  @Id
  @Column(name = "vehicle_entity_id")
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

  @Column(name = "position_x")
  private Integer positionX;

  @Column(name = "position_y")
  private Integer positionY;

  @Column(name = "position_z")
  private Integer positionZ;

  @Column(name = "total_distance", nullable = false)
  private BigDecimal totalDistance = BigDecimal.ZERO;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "destroyed_at")
  private OffsetDateTime destroyedAt;

  @Column(name = "last_updated", nullable = false)
  private OffsetDateTime lastUpdated;

  @Column(name = "source_file", nullable = false, columnDefinition = "TEXT")
  private String sourceFile;

  @Column(name = "source_log_hash", nullable = false, length = 64)
  private String sourceLogHash;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
    if (totalDistance == null) {
      totalDistance = BigDecimal.ZERO;
    }
  }
}
