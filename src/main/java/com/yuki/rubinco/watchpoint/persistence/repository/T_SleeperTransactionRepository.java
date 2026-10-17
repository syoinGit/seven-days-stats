package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_SleeperTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_SleeperTransactionRepository extends JpaRepository<T_SleeperTransaction, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);

  Optional<T_SleeperTransaction> findBySourceLogHash(String sourceLogHash);
}
