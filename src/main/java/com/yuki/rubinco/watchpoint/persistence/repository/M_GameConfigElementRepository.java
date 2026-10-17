package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.M_GameConfigElement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_GameConfigElementRepository extends JpaRepository<M_GameConfigElement, Long> {

  boolean existsBySourceHash(String sourceHash);

  List<M_GameConfigElement> findByConfigName(String configName);
}
