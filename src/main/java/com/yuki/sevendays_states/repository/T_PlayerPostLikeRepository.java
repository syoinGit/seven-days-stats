package com.yuki.sevendays_states.repository;

import com.yuki.sevendays_states.entity.T_PlayerPostLike;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_PlayerPostLikeRepository extends JpaRepository<T_PlayerPostLike, Long> {

  long countByPostId(Long postId);

  Optional<T_PlayerPostLike> findByPostIdAndAccountId(Long postId, Long accountId);

  List<T_PlayerPostLike> findAllByPostId(Long postId);

  void deleteAllByPostId(Long postId);
}
