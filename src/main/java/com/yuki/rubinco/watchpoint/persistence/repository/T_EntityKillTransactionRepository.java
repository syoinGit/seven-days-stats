package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_EntityKillTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_EntityKillTransactionRepository extends JpaRepository<T_EntityKillTransaction, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);
}
