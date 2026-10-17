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
@Table(name = "M_VEHICLE")
public class M_Vehicle {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "vehicle_key", nullable = false, unique = true, length = 180)
  private String vehicleKey;

  @Column(name = "entity_class_key", length = 180)
  private String entityClassKey;

  @Column(name = "display_name_key", length = 260)
  private String displayNameKey;

  @Column(name = "vehicle_type", length = 80)
  private String vehicleType;

  @Column(name = "raw_xml", nullable = false, columnDefinition = "TEXT")
  private String rawXml;
}
