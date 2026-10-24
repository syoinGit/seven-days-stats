package com.yuki.rubinco.portal.repository;

import com.yuki.rubinco.portal.entity.M_GamePortalState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface M_GamePortalStateRepository extends JpaRepository<M_GamePortalState, String> {
  // Every site start locks the same rows in the same order, including across app instances.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select state from M_GamePortalState state order by state.gameId")
  List<M_GamePortalState> findAllForUpdate();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select state from M_GamePortalState state where state.gameId = :gameId")
  Optional<M_GamePortalState> findForUpdate(@Param("gameId") String gameId);
}
