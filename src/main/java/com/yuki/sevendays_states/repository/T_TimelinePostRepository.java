package com.yuki.sevendays_states.repository;

import com.yuki.sevendays_states.entity.T_TimelinePost;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_TimelinePostRepository extends JpaRepository<T_TimelinePost, Long> {
  Page<T_TimelinePost> findAllByVisibleTrueOrderByPublishedAtDescIdDesc(Pageable pageable);
  boolean existsBySourceHash(String sourceHash);
  boolean existsByActorPlayerIdAndPostTypeAndPublishedAtAfter(
      Long actorPlayerId, String postType, OffsetDateTime after);
  boolean existsByPostTypeAndPublishedAtAfter(String postType, OffsetDateTime after);
  Optional<T_TimelinePost> findTopByPostTypeAndImageUrlIsNotNullOrderByPublishedAtDesc(
      String postType);
  Optional<T_TimelinePost> findTopByPostTypeOrderByPublishedAtDesc(String postType);
  boolean existsByPostTypeAndCoordinateAndPublishedAtAfter(
      String postType, String coordinate, OffsetDateTime after);
}
