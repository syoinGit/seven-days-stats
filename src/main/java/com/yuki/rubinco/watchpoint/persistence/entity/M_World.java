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
@Table(name = "M_WORLD")
public class M_World {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "world_name", nullable = false, unique = true, length = 160)
  private String worldName;

  @Column(name = "height_map_size")
  private Integer heightMapSize;

  @Column(name = "generation_seed", length = 160)
  private String generationSeed;

  @Column(name = "raw_xml", columnDefinition = "TEXT")
  private String rawXml;
}
