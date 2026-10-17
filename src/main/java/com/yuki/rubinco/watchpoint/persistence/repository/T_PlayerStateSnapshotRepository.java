package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerStateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_PlayerStateSnapshotRepository extends JpaRepository<T_PlayerStateSnapshot, Long> {

  boolean existsBySourceHash(String sourceHash);
}
