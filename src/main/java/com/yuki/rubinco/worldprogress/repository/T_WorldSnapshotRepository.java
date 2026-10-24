package com.yuki.rubinco.worldprogress.repository;

import com.yuki.rubinco.worldprogress.entity.T_WorldSnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_WorldSnapshotRepository extends JpaRepository<T_WorldSnapshot, Long> {
  Optional<T_WorldSnapshot> findByGameWorldIdAndSourceSha256AndParserVersion(
      Long gameWorldId, String sourceSha256, String parserVersion);
}
