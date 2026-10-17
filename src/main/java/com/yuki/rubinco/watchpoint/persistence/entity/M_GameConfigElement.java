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
@Table(name = "M_GAME_CONFIG_ELEMENT")
public class M_GameConfigElement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "config_name", nullable = false, length = 120)
  private String configName;

  @Column(name = "element_name", nullable = false, length = 120)
  private String elementName;

  @Column(name = "entity_key", nullable = false, length = 260)
  private String entityKey;

  @Column(name = "extends_key", length = 260)
  private String extendsKey;

  @Column(name = "display_name_key", length = 260)
  private String displayNameKey;

  @Column(name = "category", length = 120)
  private String category;

  @Column(name = "source_hash", nullable = false, unique = true, length = 64)
  private String sourceHash;

  @Column(name = "raw_xml", nullable = false, columnDefinition = "TEXT")
  private String rawXml;
}
