package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.M_GameEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_GameEntityRepository extends JpaRepository<M_GameEntity, Long> {

  Optional<M_GameEntity> findByEntityKey(String entityKey);
}
