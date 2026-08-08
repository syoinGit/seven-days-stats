package com.yuki.sevendays_states.repository;

import com.yuki.sevendays_states.entity.T_TimelinePost;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_TimelinePostRepository extends JpaRepository<T_TimelinePost, Long> {
  Page<T_TimelinePost> findAllByOrderByPublishedAtDescIdDesc(Pageable pageable);
  boolean existsBySourceHash(String sourceHash);
  boolean existsByActorPlayerIdAndPostTypeAndPublishedAtAfter(
      Long actorPlayerId, String postType, OffsetDateTime after);
}
