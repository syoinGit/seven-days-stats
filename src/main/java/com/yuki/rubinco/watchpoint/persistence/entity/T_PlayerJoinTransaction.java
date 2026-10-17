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
@Table(name = "T_PLAYER_JOIN_TRANSACTION")
public class T_PlayerJoinTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "player_join_transaction_id")
  private Long id;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "player_name", nullable = false, columnDefinition = "TEXT")
  private String playerName;

  @Column(name = "player_entity_id", nullable = false)
  private Integer playerEntityId;

  @Column(name = "player_id")
  private Long playerId;

  @Column(name = "platform_id", columnDefinition = "TEXT")
  private String platformId;

  @Column(name = "cross_platform_id", columnDefinition = "TEXT")
  private String crossPlatformId;

  @Column(name = "position_x")
  private Integer positionX;

  @Column(name = "position_y")
  private Integer positionY;

  @Column(name = "position_z")
  private Integer positionZ;

  @Column(name = "join_reason", length = 100)
  private String joinReason;

  @Column(name = "client_number")
  private Integer clientNumber;

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
