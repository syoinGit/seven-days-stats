package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_LevelXpSummaryTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_LevelXpSummaryTransactionRepository extends JpaRepository<T_LevelXpSummaryTransaction, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);

  Optional<T_LevelXpSummaryTransaction> findBySourceLogHash(String sourceLogHash);
}
