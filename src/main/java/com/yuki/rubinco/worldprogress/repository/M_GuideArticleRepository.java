package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.M_GuideArticle;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_GuideArticleRepository extends JpaRepository<M_GuideArticle, Long> {
  List<M_GuideArticle> findByGameIdOrderBySortOrderAscIdAsc(String gameId);
}
