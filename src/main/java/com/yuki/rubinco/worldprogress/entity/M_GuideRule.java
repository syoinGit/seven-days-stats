package com.yuki.rubinco.worldprogress.entity;

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
@Table(name = "M_GUIDE_RULE")
public class M_GuideRule {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "guide_rule_id")
  private Long id;

  @Column(name = "guide_article_id", nullable = false)
  private Long guideArticleId;

  @Column(name = "progress_definition_id", nullable = false)
  private Long progressDefinitionId;

  /** SHOW: all must hold. HIDE: any hides the article. */
  @Column(name = "rule_type", nullable = false, length = 10)
  private String ruleType;

  @Column(name = "required_state", nullable = false, length = 20)
  private String requiredState;
}
