package com.yuki.rubinco.account.repository;

import com.yuki.rubinco.account.entity.M_WebAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_WebAccountRepository extends JpaRepository<M_WebAccount, Long> {

  Optional<M_WebAccount> findByLoginId(String loginId);

  Optional<M_WebAccount> findByCognitoSub(String cognitoSub);

  boolean existsByLoginId(String loginId);

  Optional<M_WebAccount> findByPlayerId(Long playerId);

  List<M_WebAccount> findAllByOrderByLoginIdAsc();
}
