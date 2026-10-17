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
@Table(name = "T_WORLD_EVENT_TRANSACTION")
public class T_WorldEventTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "world_event_transaction_id")
  private Long id;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "event_type", nullable = false, length = 50)
  private String eventType;

  @Column(name = "actor_player_name", columnDefinition = "TEXT")
  private String actorPlayerName;

  @Column(name = "actor_player_entity_id")
  private Integer actorPlayerEntityId;

  @Column(name = "player_id")
  private Long playerId;

  @Column(name = "detail_text", columnDefinition = "TEXT")
  private String detailText;

  @Column(name = "position_x")
  private Integer positionX;

  @Column(name = "position_y")
  private Integer positionY;

  @Column(name = "position_z")
  private Integer positionZ;

  @Column(name = "target_position_x")
  private Integer targetPositionX;

  @Column(name = "target_position_y")
  private Integer targetPositionY;

  @Column(name = "target_position_z")
  private Integer targetPositionZ;

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
  }
}
