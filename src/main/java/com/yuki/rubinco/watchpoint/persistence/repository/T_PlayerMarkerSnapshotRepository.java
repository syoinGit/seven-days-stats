package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerMarkerSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_PlayerMarkerSnapshotRepository extends JpaRepository<T_PlayerMarkerSnapshot, Long> {

  boolean existsBySourceHash(String sourceHash);
}
