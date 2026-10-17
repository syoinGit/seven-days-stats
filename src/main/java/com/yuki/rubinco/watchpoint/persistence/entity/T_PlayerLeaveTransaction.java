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
@Table(name = "T_PLAYER_LEAVE_TRANSACTION")
public class T_PlayerLeaveTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "player_leave_transaction_id")
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
