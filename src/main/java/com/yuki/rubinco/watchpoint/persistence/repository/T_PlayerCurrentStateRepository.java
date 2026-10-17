package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerCurrentState;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_PlayerCurrentStateRepository extends JpaRepository<T_PlayerCurrentState, Integer> {

  boolean existsByLastUpdatedAfter(OffsetDateTime observedAt);

  List<T_PlayerCurrentState> findByOnlineTrue();

  List<T_PlayerCurrentState> findByOnlineTrueAndPlayerEntityIdNotIn(Collection<Integer> playerEntityIds);

  List<T_PlayerCurrentState> findByPlayerId(Long playerId);

  List<T_PlayerCurrentState> findByCrossPlatformIdIn(Collection<String> crossPlatformIds);

  List<T_PlayerCurrentState> findByPlatformIdIn(Collection<String> platformIds);
}
