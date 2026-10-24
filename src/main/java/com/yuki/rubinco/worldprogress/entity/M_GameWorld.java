package com.yuki.rubinco.worldprogress.entity;

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

/** An operator-registered world generation. History never continues across generations. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "M_GAME_WORLD")
public class M_GameWorld {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "game_world_id")
  private Long id;

  @Column(name = "game_id", nullable = false, length = 40)
  private String gameId;

  @Column(name = "world_key", nullable = false, length = 60)
  private String worldKey;

  @Column(name = "generation", nullable = false)
  private int generation;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(name = "configured_note", length = 400)
  private String configuredNote;

  @Column(name = "published", nullable = false)
  private boolean published = true;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "latest_snapshot_id")
  private Long latestSnapshotId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "activated_at")
  private OffsetDateTime activatedAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = OffsetDateTime.now();
  }
}
