package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerJoinTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_PlayerJoinTransactionRepository extends JpaRepository<T_PlayerJoinTransaction, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);
}
