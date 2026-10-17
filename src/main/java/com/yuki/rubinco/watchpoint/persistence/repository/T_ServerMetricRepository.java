package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_ServerMetric;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_ServerMetricRepository extends JpaRepository<T_ServerMetric, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);

  Optional<T_ServerMetric> findTopByOrderByOccurredAtDesc();
}
