package com.yuki.rubinco.timeline.repository;

import com.yuki.rubinco.timeline.entity.T_TimelinePostReaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_TimelinePostReactionRepository extends JpaRepository<T_TimelinePostReaction, Long> {
  Optional<T_TimelinePostReaction> findByTimelinePostIdAndAccountId(Long timelinePostId, Long accountId);
  List<T_TimelinePostReaction> findAllByTimelinePostId(Long timelinePostId);
  List<T_TimelinePostReaction> findAllByTimelinePostIdIn(List<Long> timelinePostIds);
  long countByTimelinePostId(Long timelinePostId);
  void deleteAllByTimelinePostId(Long timelinePostId);
}
