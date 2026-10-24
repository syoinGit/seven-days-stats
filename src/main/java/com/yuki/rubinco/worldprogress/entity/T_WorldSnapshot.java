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
@Table(name = "T_WORLD_SNAPSHOT")
public class T_WorldSnapshot {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "world_snapshot_id")
  private Long id;

  @Column(name = "game_world_id", nullable = false)
  private Long gameWorldId;

  @Column(name = "source_sha256", nullable = false, length = 64)
  private String sourceSha256;

  @Column(name = "container_version")
  private Integer containerVersion;

  @Column(name = "parser_version", nullable = false, length = 40)
  private String parserVersion;

  @Column(name = "captured_at", nullable = false)
  private OffsetDateTime capturedAt;

  @Column(name = "source_modified_at")
  private OffsetDateTime sourceModifiedAt;

  @Column(name = "parsed_at", nullable = false)
  private OffsetDateTime parsedAt;

  @Column(name = "validation_status", nullable = false, length = 20)
  private String validationStatus;

  @Column(name = "generation_json", columnDefinition = "TEXT")
  private String generationJson;

  @Column(name = "extracted_json", nullable = false, columnDefinition = "TEXT")
  private String extractedJson;
}
