package com.yuki.rubinco.worldprogress.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "M_GUIDE_ARTICLE")
public class M_GuideArticle {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "guide_article_id")
  private Long id;

  @Column(name = "game_id", nullable = false, length = 40)
  private String gameId;

  @Column(name = "title", nullable = false, length = 120)
  private String title;

  @Column(name = "body", nullable = false, columnDefinition = "TEXT")
  private String body;

  @Column(name = "game_version", length = 40)
  private String gameVersion;

  @Column(name = "reference_url", length = 300)
  private String referenceUrl;

  @Column(name = "reference_label", length = 80)
  private String referenceLabel;

  @Column(name = "verified_on")
  private LocalDate verifiedOn;

  @Column(name = "spoiler", nullable = false)
  private boolean spoiler;

  @Column(name = "published", nullable = false)
  private boolean published;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = OffsetDateTime.now();
    if (updatedAt == null) updatedAt = createdAt;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
