package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "T_SERVER_METRIC")
public class T_ServerMetric {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "server_metric_id")
  private Long id;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "uptime_minutes")
  private BigDecimal uptimeMinutes;

  @Column(name = "fps")
  private BigDecimal fps;

  @Column(name = "heap_mb")
  private BigDecimal heapMb;

  @Column(name = "max_heap_mb")
  private BigDecimal maxHeapMb;

  @Column(name = "chunks")
  private Integer chunks;

  @Column(name = "cgo")
  private Integer cgo;

  @Column(name = "player_count")
  private Integer playerCount;

  @Column(name = "zombie_count")
  private Integer zombieCount;

  @Column(name = "entity_count")
  private Integer entityCount;

  @Column(name = "entity_count_detail")
  private Integer entityCountDetail;

  @Column(name = "item_count")
  private Integer itemCount;

  @Column(name = "co")
  private Integer co;

  @Column(name = "rss_mb")
  private BigDecimal rssMb;

  @Column(name = "source_file", nullable = false, columnDefinition = "TEXT")
  private String sourceFile;

  @Column(name = "source_log_hash", nullable = false, unique = true, length = 64)
  private String sourceLogHash;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }
}
