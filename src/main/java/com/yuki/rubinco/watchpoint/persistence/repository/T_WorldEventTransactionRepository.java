package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_WorldEventTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_WorldEventTransactionRepository extends JpaRepository<T_WorldEventTransaction, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);
}
