package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_PLAYER_STATUS")
public class T_PlayerStatus {
  @Id
  @Column(name = "player_id")
  private Long playerId;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "source", nullable = false, length = 30)
  private String source;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
