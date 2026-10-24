package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface M_GameWorldRepository extends JpaRepository<M_GameWorld, Long> {
  Optional<M_GameWorld> findFirstByGameIdAndActiveTrue(String gameId);

  List<M_GameWorld> findByGameIdOrderByWorldKeyAscGenerationDesc(String gameId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select world from M_GameWorld world where world.gameId = :gameId order by world.id")
  List<M_GameWorld> findByGameIdForUpdate(@Param("gameId") String gameId);
}
