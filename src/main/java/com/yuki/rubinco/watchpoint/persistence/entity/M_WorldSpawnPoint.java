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
@Table(name = "M_WORLD_SPAWN_POINT")
public class M_WorldSpawnPoint {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "source_hash", nullable = false, unique = true, length = 64)
  private String sourceHash;

  @Column(name = "world_name", nullable = false, length = 160)
  private String worldName;

  @Column(name = "x", nullable = false)
  private Double x;

  @Column(name = "y", nullable = false)
  private Double y;

  @Column(name = "z", nullable = false)
  private Double z;

  @Column(name = "rotation_x")
  private Double rotationX;

  @Column(name = "rotation_y")
  private Double rotationY;

  @Column(name = "rotation_z")
  private Double rotationZ;
}
