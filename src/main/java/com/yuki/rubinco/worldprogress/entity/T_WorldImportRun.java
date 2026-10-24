package com.yuki.rubinco.worldprogress.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "T_WORLD_IMPORT_RUN")
public class T_WorldImportRun {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "world_import_run_id")
  private Long id;

  @Column(name = "game_id", nullable = false, length = 40)
  private String gameId;

  @Column(name = "game_world_id")
  private Long gameWorldId;

  @Column(name = "trigger_type", nullable = false, length = 20)
  private String triggerType;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "requested_by", length = 160)
  private String requestedBy;

  @Column(name = "requested_at", nullable = false)
  private OffsetDateTime requestedAt;

  @Column(name = "started_at")
  private OffsetDateTime startedAt;

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "source_size")
  private Long sourceSize;

  @Column(name = "source_modified_at")
  private OffsetDateTime sourceModifiedAt;

  @Column(name = "source_sha256", length = 64)
  private String sourceSha256;

  @Column(name = "decompressed_bytes")
  private Long decompressedBytes;

  @Column(name = "container_version")
  private Integer containerVersion;

  @Column(name = "parser_version", length = 40)
  private String parserVersion;

  @Column(name = "world_snapshot_id")
  private Long worldSnapshotId;

  @Column(name = "error_code", length = 40)
  private String errorCode;

  @Column(name = "error_message", length = 500)
  private String errorMessage;
}
