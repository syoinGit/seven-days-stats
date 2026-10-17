package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.T_WorldTimeObservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_WorldTimeObservationRepository extends JpaRepository<T_WorldTimeObservation, Long> {

  boolean existsBySourceHash(String sourceHash);
}
