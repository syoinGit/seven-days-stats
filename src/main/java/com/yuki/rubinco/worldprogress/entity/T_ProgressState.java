package com.yuki.rubinco.worldprogress.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "T_PROGRESS_STATE")
public class T_ProgressState {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "progress_state_id")
  private Long id;

  @Column(name = "world_snapshot_id", nullable = false)
  private Long worldSnapshotId;

  @Column(name = "progress_definition_id", nullable = false)
  private Long progressDefinitionId;

  @Column(name = "state", nullable = false, length = 20)
  private String state;
}
