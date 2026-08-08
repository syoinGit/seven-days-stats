package com.yuki.sevendays_states.repository;

import com.yuki.sevendays_states.entity.T_AiComment;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface T_AiCommentRepository extends JpaRepository<T_AiComment, Long> {

  Optional<T_AiComment> findByDiaryDate(LocalDate diaryDate);

  Optional<T_AiComment> findTopByDiaryDateIsNotNullOrderByDiaryDateDescPublishedAtDesc();

  Optional<T_AiComment> findTopByOrderByPublishedAtDesc();

  Optional<T_AiComment> findTopBySourceTypeOrderByPublishedAtDesc(String sourceType);

  List<T_AiComment> findTop20BySourceTypeOrderByPublishedAtDesc(String sourceType);

  List<T_AiComment> findTop100ByDiaryDateIsNotNullOrderByDiaryDateDescPublishedAtDesc();

  long countByAiGeneratedTrueAndDiaryDateIsNullAndPublishedAtGreaterThanEqual(OffsetDateTime from);

  boolean existsByPostTypeAndPublishedAtGreaterThanEqual(String postType, OffsetDateTime from);

}
