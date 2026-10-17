package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.M_Block;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_BlockRepository extends JpaRepository<M_Block, Long> {

  Optional<M_Block> findByBlockKey(String blockKey);
}
