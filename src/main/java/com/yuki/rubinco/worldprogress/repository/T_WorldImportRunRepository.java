package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.T_WorldImportRun;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface T_WorldImportRunRepository extends JpaRepository<T_WorldImportRun, Long> {
  Page<T_WorldImportRun> findByGameIdOrderByRequestedAtDescIdDesc(String gameId, Pageable pageable);

  boolean existsByGameIdAndStatusIn(String gameId, Collection<String> statuses);

  Optional<T_WorldImportRun> findFirstByGameIdAndStatusOrderByRequestedAtAscIdAsc(
      String gameId, String status);

  List<T_WorldImportRun> findByGameIdAndStatus(String gameId, String status);

  Optional<T_WorldImportRun> findFirstByGameWorldIdAndStatusInOrderByFinishedAtDescIdDesc(
      Long gameWorldId, Collection<String> statuses);

  Optional<T_WorldImportRun> findFirstByGameIdAndStatusOrderByFinishedAtDescIdDesc(
      String gameId, String status);

  @Modifying
  @Query("delete from T_WorldImportRun run where run.gameId = :gameId and run.requestedAt < :before"
      + " and run.status in ('SUCCEEDED', 'UNCHANGED', 'FAILED')")
  int deleteFinishedBefore(@Param("gameId") String gameId, @Param("before") OffsetDateTime before);
}
