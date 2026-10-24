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
@Table(name = "T_PROGRESS_EVENT")
public class T_ProgressEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "progress_event_id")
  private Long id;

  @Column(name = "game_world_id", nullable = false)
  private Long gameWorldId;

  @Column(name = "progress_definition_id", nullable = false)
  private Long progressDefinitionId;

  @Column(name = "world_snapshot_id", nullable = false)
  private Long worldSnapshotId;

  @Column(name = "previous_state", length = 20)
  private String previousState;

  @Column(name = "new_state", nullable = false, length = 20)
  private String newState;

  @Column(name = "change_kind", nullable = false, length = 20)
  private String changeKind;

  @Column(name = "first_observed_at", nullable = false)
  private OffsetDateTime firstObservedAt;
}
