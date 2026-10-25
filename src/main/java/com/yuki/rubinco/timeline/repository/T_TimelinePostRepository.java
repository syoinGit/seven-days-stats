package com.yuki.rubinco.timeline.repository;

import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface T_TimelinePostRepository extends JpaRepository<T_TimelinePost, Long> {
  /** Serialize reaction toggles and deletion for a post, including the first reaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select post from T_TimelinePost post where post.id = :id")
  Optional<T_TimelinePost> findByIdForUpdate(@Param("id") Long id);

  /** Infinite scrolling needs only the next-page flag, not a full-history count. */
  @Query("""
      select post from T_TimelinePost post
      where post.gameId = :gameId and post.visible = true
      order by post.publishedAt desc, post.id desc
      """)
  Slice<T_TimelinePost> findVisibleFeed(@Param("gameId") String gameId, Pageable pageable);

  Page<T_TimelinePost> findAllByGameIdInAndVisibleTrueOrderByPublishedAtDescIdDesc(
      List<String> gameIds, Pageable pageable);
  Page<T_TimelinePost> findAllByGameIdAndVisibleTrueOrderByPublishedAtDescIdDesc(String gameId, Pageable pageable);
  Optional<T_TimelinePost> findTopByGameIdAndVisibleTrueOrderByPublishedAtDescIdDesc(String gameId);
  Optional<T_TimelinePost> findTopByGameIdAndVisibleTrueAndProgressIsNotNullOrderByPublishedAtDescIdDesc(
      String gameId);
  Optional<T_TimelinePost> findTopByGameIdAndVisibleTrueAndStageNameIsNotNullOrderByPublishedAtDescIdDesc(
      String gameId);
  Optional<T_TimelinePost> findByEventIdAndGameId(String eventId, String gameId);
  boolean existsBySourceHash(String sourceHash);
  boolean existsByGameIdAndActorPlayerIdAndPostTypeAndPublishedAtAfter(
      String gameId, Long actorPlayerId, String postType, OffsetDateTime after);
  boolean existsByGameIdAndPostTypeAndPublishedAtAfter(
      String gameId, String postType, OffsetDateTime after);
  Optional<T_TimelinePost> findTopByGameIdAndPostTypeOrderByPublishedAtDesc(
      String gameId, String postType);
}
