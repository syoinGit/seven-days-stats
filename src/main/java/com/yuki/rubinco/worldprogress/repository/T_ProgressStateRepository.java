package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.T_ProgressState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_ProgressStateRepository extends JpaRepository<T_ProgressState, Long> {
  List<T_ProgressState> findByWorldSnapshotId(Long worldSnapshotId);
}
