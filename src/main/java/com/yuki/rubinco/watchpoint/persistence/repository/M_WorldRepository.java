package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.M_World;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_WorldRepository extends JpaRepository<M_World, Long> {

  Optional<M_World> findByWorldName(String worldName);
}
