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
@Table(name = "M_WORLD_POI")
public class M_WorldPoi {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "source_hash", nullable = false, unique = true, length = 64)
  private String sourceHash;

  @Column(name = "world_name", nullable = false, length = 160)
  private String worldName;

  @Column(name = "game_name", length = 160)
  private String gameName;

  @Column(name = "poi_name", nullable = false, length = 180)
  private String poiName;

  @Column(name = "poi_type", length = 80)
  private String poiType;

  @Column(name = "category", length = 80)
  private String category;

  @Column(name = "x", nullable = false)
  private Integer x;

  @Column(name = "y", nullable = false)
  private Integer y;

  @Column(name = "z", nullable = false)
  private Integer z;

  @Column(name = "rotation")
  private Integer rotation;
}
