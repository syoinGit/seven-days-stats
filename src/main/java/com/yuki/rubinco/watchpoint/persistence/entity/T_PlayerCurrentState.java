package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "T_PLAYER_CURRENT_STATE")
public class T_PlayerCurrentState {

  @Id
  @Column(name = "player_entity_id")
  private Integer playerEntityId;

  @Column(name = "player_id")
  private Long playerId;

  @Column(name = "player_name", nullable = false, columnDefinition = "TEXT")
  private String playerName;

  @Column(name = "position_x", nullable = false)
  private Integer positionX;

  @Column(name = "position_y")
  private Integer positionY;

  @Column(name = "position_z", nullable = false)
  private Integer positionZ;

  @Column(name = "rotation_x")
  private BigDecimal rotationX;

  @Column(name = "rotation_y")
  private BigDecimal rotationY;

  @Column(name = "rotation_z")
  private BigDecimal rotationZ;

  @Column(name = "health")
  private Integer health;

  @Column(name = "deaths")
  private Integer deaths;

  @Column(name = "zombies")
  private Integer zombies;

  @Column(name = "players")
  private Integer players;

  @Column(name = "score")
  private Integer score;

  @Column(name = "level")
  private Integer level;

  @Column(name = "platform_id", columnDefinition = "TEXT")
  private String platformId;

  @Column(name = "cross_platform_id", columnDefinition = "TEXT")
  private String crossPlatformId;

  @Column(name = "ping")
  private Integer ping;

  @Column(name = "online", nullable = false)
  private boolean online = true;

  @Column(name = "last_updated", nullable = false)
  private OffsetDateTime lastUpdated;
}
