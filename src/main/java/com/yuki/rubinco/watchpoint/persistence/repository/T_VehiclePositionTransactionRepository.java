package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_VehiclePositionTransaction;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_VehiclePositionTransactionRepository extends JpaRepository<T_VehiclePositionTransaction, Long> {

  boolean existsBySourceLogHash(String sourceLogHash);

  Optional<T_VehiclePositionTransaction> findTopByAttributedPlayerIdAndMovementValidTrueAndOccurredAtBetweenOrderByOccurredAtDescIdDesc(
      Long playerId, OffsetDateTime from, OffsetDateTime to);
}
