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
@Table(name = "M_SERVER_CONFIG_SETTING")
public class M_ServerConfigSetting {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "setting_key", nullable = false, unique = true, length = 180)
  private String settingKey;

  @Column(name = "setting_value", columnDefinition = "TEXT")
  private String settingValue;

  @Column(name = "is_sensitive", nullable = false)
  private boolean sensitive;
}
