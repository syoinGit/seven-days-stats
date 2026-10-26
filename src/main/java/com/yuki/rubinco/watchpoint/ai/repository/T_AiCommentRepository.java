package com.yuki.rubinco.watchpoint.ai.repository;

import com.yuki.rubinco.watchpoint.ai.entity.T_AiComment;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_AiCommentRepository extends JpaRepository<T_AiComment, Long> {

  Optional<T_AiComment> findTopByOrderByPublishedAtDesc();

  Optional<T_AiComment> findTopBySourceTypeOrderByPublishedAtDesc(String sourceType);

  List<T_AiComment> findTop20BySourceTypeOrderByPublishedAtDesc(String sourceType);

  long countByAiGeneratedTrueAndDiaryDateIsNullAndPublishedAtGreaterThanEqual(OffsetDateTime from);

  boolean existsByPostTypeAndPublishedAtGreaterThanEqual(String postType, OffsetDateTime from);

}
