package com.yuki.rubinco.portal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "M_GAME_PORTAL_STATE")
public class M_GamePortalState {
  @Id
  @Column(name = "game_id", length = 40)
  private String gameId;

  @Column(name = "server_status", nullable = false, length = 20)
  private String serverStatus = "offline";

  @Column(name = "progress", nullable = false)
  private int progress;

  @Column(name = "stage_name", nullable = false, length = 160)
  private String stageName;

  @Column(name = "schedule_text", nullable = false, length = 200)
  private String scheduleText;

  @Column(name = "game_version", nullable = false, length = 80)
  private String gameVersion;

  @Column(name = "connection_text", length = 240)
  private String connectionText;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = OffsetDateTime.now();
  }
}
