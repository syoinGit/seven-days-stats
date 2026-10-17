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
@Table(name = "M_BLOCK")
public class M_Block {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "block_key", nullable = false, unique = true, length = 180)
  private String blockKey;

  @Column(name = "display_name_key", length = 260)
  private String displayNameKey;

  @Column(name = "material", length = 120)
  private String material;

  @Column(name = "shape", length = 120)
  private String shape;

  @Column(name = "category", length = 120)
  private String category;

  @Column(name = "tags", columnDefinition = "TEXT")
  private String tags;

  @Column(name = "raw_xml", nullable = false, columnDefinition = "TEXT")
  private String rawXml;
}
