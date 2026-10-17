package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerLeaveTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_PlayerLeaveTransactionRepository extends JpaRepository<T_PlayerLeaveTransaction, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);
}
