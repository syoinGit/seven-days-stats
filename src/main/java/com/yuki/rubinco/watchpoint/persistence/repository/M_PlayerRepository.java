package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.M_Player;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_PlayerRepository extends JpaRepository<M_Player, Long> {

  Optional<M_Player> findByPlayerKey(String playerKey);

  Optional<M_Player> findFirstByPlayerNameOrderByLastSeenAtDesc(String playerName);

  List<M_Player> findAllByOrderByPlayerNameAsc();

  List<M_Player> findByPlayerKeyInOrderByIdAsc(Collection<String> playerKeys);

  Optional<M_Player> findFirstByPlatformIgnoreCaseAndUserIdOrderByIdAsc(String platform, String userId);

  Optional<M_Player> findFirstByNativePlatformIgnoreCaseAndNativeUserIdOrderByIdAsc(
      String nativePlatform,
      String nativeUserId);
}
