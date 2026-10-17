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
@Table(name = "T_WORLD_TIME_OBSERVATION")
public class T_WorldTimeObservation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "world_time_observation_id")
  private Long id;

  @Column(name = "observed_at", nullable = false)
  private OffsetDateTime observedAt;

  @Column(name = "game_day", nullable = false)
  private Integer gameDay;

  @Column(name = "game_hour", nullable = false)
  private Integer gameHour;

  @Column(name = "game_minute", nullable = false)
  private Integer gameMinute;

  @Column(name = "source", nullable = false, columnDefinition = "TEXT")
  private String source;

  @Column(name = "source_hash", nullable = false, unique = true, length = 64)
  private String sourceHash;

  @Column(name = "raw_response", nullable = false, columnDefinition = "TEXT")
  private String rawResponse;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }
}
