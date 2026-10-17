package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerPositionTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_PlayerPositionTransactionRepository extends JpaRepository<T_PlayerPositionTransaction, Long> {

  boolean existsBySourceEventHash(String sourceEventHash);

  Optional<T_PlayerPositionTransaction> findTopByPlayerIdOrderByOccurredAtDescIdDesc(Long playerId);

  Optional<T_PlayerPositionTransaction> findTopByPlayerEntityIdOrderByOccurredAtDescIdDesc(Integer playerEntityId);
}
