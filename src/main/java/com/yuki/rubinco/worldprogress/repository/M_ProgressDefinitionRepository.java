package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.M_ProgressDefinition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_ProgressDefinitionRepository extends JpaRepository<M_ProgressDefinition, Long> {
  List<M_ProgressDefinition> findByGameIdOrderBySortOrderAscIdAsc(String gameId);
}
