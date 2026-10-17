package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_IMPORT_RUN")
public class T_ImportRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "started_at", nullable = false)
  private LocalDateTime startedAt;

  @Column(name = "finished_at")
  private LocalDateTime finishedAt;

  @Column(name = "environment_name", length = 80)
  private String environmentName;

  @Column(name = "source_root", nullable = false, length = 500)
  private String sourceRoot;

  @Column(name = "config_dir", length = 500)
  private String configDir;

  @Column(name = "data_dir", length = 500)
  private String dataDir;

  @Column(name = "game_dir", length = 500)
  private String gameDir;

  @Column(name = "status", nullable = false, length = 40)
  private String status;

  @Column(name = "message", columnDefinition = "TEXT")
  private String message;
}
