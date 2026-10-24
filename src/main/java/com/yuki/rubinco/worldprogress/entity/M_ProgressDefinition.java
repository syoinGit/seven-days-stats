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

/** Internal key and save source are kept apart from the (editable) display name. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "M_PROGRESS_DEFINITION")
public class M_ProgressDefinition {
  public static final String SUPPORTED = "SUPPORTED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "progress_definition_id")
  private Long id;

  @Column(name = "game_id", nullable = false, length = 40)
  private String gameId;

  @Column(name = "progress_key", nullable = false, length = 60)
  private String progressKey;

  @Column(name = "category", nullable = false, length = 20)
  private String category;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(name = "description", length = 400)
  private String description;

  @Column(name = "support_status", nullable = false, length = 20)
  private String supportStatus;

  @Column(name = "source_kind", nullable = false, length = 40)
  private String sourceKind;

  @Column(name = "source_object_id")
  private Integer sourceObjectId;

  @Column(name = "source_note", length = 200)
  private String sourceNote;

  @Column(name = "spoiler", nullable = false)
  private boolean spoiler;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  public boolean isSupported() {
    return SUPPORTED.equals(supportStatus);
  }
}
