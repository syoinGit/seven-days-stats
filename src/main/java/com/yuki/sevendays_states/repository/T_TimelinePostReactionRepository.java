package com.yuki.sevendays_states.repository;

import com.yuki.sevendays_states.entity.T_TimelinePostReaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_TimelinePostReactionRepository extends JpaRepository<T_TimelinePostReaction, Long> {
  Optional<T_TimelinePostReaction> findByTimelinePostIdAndAccountId(Long timelinePostId, Long accountId);
  List<T_TimelinePostReaction> findAllByTimelinePostId(Long timelinePostId);
  void deleteAllByTimelinePostId(Long timelinePostId);
}
