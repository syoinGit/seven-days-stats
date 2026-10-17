package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_PLAYER_STATE_SNAPSHOT")
public class T_PlayerStateSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "import_run_id")
  private T_ImportRun importRun;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "player_id")
  private M_Player player;

  @Column(name = "world_name", nullable = false, length = 160)
  private String worldName;

  @Column(name = "game_name", nullable = false, length = 160)
  private String gameName;

  @Column(name = "captured_at", nullable = false)
  private LocalDateTime capturedAt;

  @Column(name = "play_group", length = 80)
  private String playGroup;

  @Column(name = "last_login")
  private LocalDateTime lastLogin;

  @Column(name = "x")
  private Integer x;

  @Column(name = "y")
  private Integer y;

  @Column(name = "z")
  private Integer z;

  @Column(name = "source_hash", nullable = false, unique = true, length = 64)
  private String sourceHash;
}
