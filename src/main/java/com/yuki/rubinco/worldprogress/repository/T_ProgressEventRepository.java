package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.T_ProgressEvent;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_ProgressEventRepository extends JpaRepository<T_ProgressEvent, Long> {
  Page<T_ProgressEvent> findByGameWorldIdOrderByFirstObservedAtDescIdDesc(
      Long gameWorldId, Pageable pageable);

  Page<T_ProgressEvent> findByGameWorldIdAndNewStateOrderByFirstObservedAtDescIdDesc(
      Long gameWorldId, String newState, Pageable pageable);

  List<T_ProgressEvent> findByWorldSnapshotId(Long worldSnapshotId);
}
