package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.M_GuideRule;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_GuideRuleRepository extends JpaRepository<M_GuideRule, Long> {
  List<M_GuideRule> findByGuideArticleIdIn(Collection<Long> articleIds);

  void deleteByGuideArticleId(Long articleId);
}
