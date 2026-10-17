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
@Table(name = "M_JAPANESE_TRANSLATION")
public class M_JapaneseTranslation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "localization_key", nullable = false, unique = true, length = 260)
  private String localizationKey;

  @Column(name = "source", length = 120)
  private String source;

  @Column(name = "entry_type", length = 120)
  private String entryType;

  @Column(name = "context", columnDefinition = "TEXT")
  private String context;

  @Column(name = "english", columnDefinition = "TEXT")
  private String english;

  @Column(name = "japanese", columnDefinition = "TEXT")
  private String japanese;

  @Column(name = "display_text", nullable = false, columnDefinition = "TEXT")
  private String displayText;

  @Column(name = "translated", nullable = false)
  private boolean translated;
}
