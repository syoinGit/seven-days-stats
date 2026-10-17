package com.yuki.rubinco.watchpoint.persistence.entity;

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
@Table(name = "M_GAME_ENTITY")
public class M_GameEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "entity_key", nullable = false, unique = true, length = 180)
  private String entityKey;

  @Column(name = "entity_type", length = 80)
  private String entityType;

  @Column(name = "display_name_key", length = 260)
  private String displayNameKey;

  @Column(name = "category", length = 120)
  private String category;

  @Column(name = "tags", columnDefinition = "TEXT")
  private String tags;

  @Column(name = "raw_xml", nullable = false, columnDefinition = "TEXT")
  private String rawXml;
}
