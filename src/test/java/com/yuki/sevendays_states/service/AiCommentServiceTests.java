package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.repository.T_AiCommentRepository;
import com.yuki.sevendays_states.repository.T_TimelinePostRepository;
import com.yuki.sevendays_states.entity.AiPostType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sevendays_states_ai_comments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.ai-comment.editor-key=test-editor-key"
})
class AiCommentServiceTests {

  @Autowired
  private AiCommentService service;

  @Autowired
  private T_AiCommentRepository repository;

  @Autowired
  private T_TimelinePostRepository timelinePostRepository;

  @Autowired
  private TimelinePostService timelinePostService;

  @BeforeEach
  void resetData() {
    timelinePostRepository.deleteAll();
    repository.deleteAll();
  }

  @Test
  void publishesAndReturnsLatestDailyDiary() {
    service.publish(LocalDate.of(2026, 8, 1), " 荒野通信 ", " 今日も生存を確認。 ", "test-editor-key");
    service.publish(LocalDate.of(2026, 8, 2), "二報", "病院の探索が進みました。", "test-editor-key");

    assertThat(service.latestDiary()).get()
        .satisfies(comment -> {
          assertThat(comment.title()).isEqualTo("二報");
          assertThat(comment.body()).isEqualTo("病院の探索が進みました。");
          assertThat(comment.sourceType()).isEqualTo("MANUAL_BETA");
        });
    assertThat(service.diaries()).hasSize(2);
  }

  @Test
  void rejectsBlankComment() {
    assertThatThrownBy(() -> service.publish(
        LocalDate.of(2026, 8, 2), "", "", "test-editor-key"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updatesExistingDiaryForSameDate() {
    LocalDate date = LocalDate.of(2026, 8, 2);
    service.publish(date, "初稿", "最初の日記", "test-editor-key");
    service.publish(date, "完成稿", "完成した日記", "test-editor-key");

    assertThat(repository.count()).isOne();
    assertThat(service.findByDiaryDate(date)).get()
        .satisfies(diary -> assertThat(diary.title()).isEqualTo("完成稿"));
  }

  @Test
  void publishesGeneratedObservationWithoutReplacingDailyDiary() {
    service.publish(LocalDate.of(2026, 8, 2), "日記", "一日の記録", "test-editor-key");
    service.publishGenerated("WATCHPOINT観測記録", "静かな探索が続いています。", "AWS_BEDROCK");

    assertThat(repository.count()).isEqualTo(2);
    assertThat(service.latestComment()).get().satisfies(comment -> {
      assertThat(comment.diaryDate()).isNull();
      assertThat(comment.body()).isEqualTo("静かな探索が続いています。");
      assertThat(comment.sourceType()).isEqualTo("AWS_BEDROCK");
    });
    assertThat(service.latestDiary()).get()
        .satisfies(comment -> assertThat(comment.title()).isEqualTo("日記"));
    assertThat(service.latestBySourceType("AWS_BEDROCK", 20))
        .singleElement()
        .satisfies(comment -> assertThat(comment.body()).isEqualTo("静かな探索が続いています。"));
  }

  @Test
  void mirrorsGeneratedAiObservationsIntoTheUnifiedTimeline() {
    var generated = service.publishGenerated("観測", "生存者の活動を確認。", "AWS_BEDROCK");

    assertThat(timelinePostRepository.findAll()).singleElement().satisfies(post -> {
      assertThat(post.getPostType()).isEqualTo("WATCHPOINT");
      assertThat(post.getSourceId()).isEqualTo(generated.id());
      assertThat(post.getMessage()).isEqualTo("生存者の活動を確認。");
      assertThat(post.getActorName()).isEqualTo("WATCHPOINT");
    });
  }

  @Test
  void displaysAiSentencesOnSeparateLinesInTheTimeline() {
    service.publishGenerated("観測", "生存者の活動を確認しました。周辺はまだ静かなようです。", "AWS_BEDROCK");

    assertThat(timelinePostService.feed(null, 0).posts()).singleElement()
        .satisfies(post -> assertThat(post.message())
            .isEqualTo("生存者の活動を確認しました。\n周辺はまだ静かなようです。"));
  }

  @Test
  void persistsAiPostTypeAndGeneratedFlag() {
    var saved = service.publishGenerated(
        "WATCHPOINT観測記録", "生存者の活動を分析しました。", "AWS_BEDROCK",
        AiPostType.PLAYER_ANALYSIS, null);

    assertThat(saved.postType()).isEqualTo(AiPostType.PLAYER_ANALYSIS);
    assertThat(saved.aiGenerated()).isTrue();
    assertThat(repository.findById(saved.id())).get().satisfies(entity -> {
      assertThat(entity.getPostType()).isEqualTo("PLAYER_ANALYSIS");
      assertThat(entity.isAiGenerated()).isTrue();
    });
    assertThat(timelinePostRepository.findAll()).singleElement()
        .satisfies(post -> {
          assertThat(post.getActorName()).isEqualTo("観測分析局");
          assertThat(post.getPostType()).isEqualTo("PLAYER_ANALYSIS");
        });
  }

  @Test
  void publishesGeneratedDiaryWithSummaryAndTags() {
    LocalDate date = LocalDate.of(2026, 8, 3);

    service.publishGeneratedDiary(
        date, "病院の灯り", "病院を中心に探索した一日。", List.of("探索", "病院", "探索"),
        "生存者たちは病院へ向かった。");

    assertThat(service.findByDiaryDate(date)).get().satisfies(diary -> {
      assertThat(diary.title()).isEqualTo("病院の灯り");
      assertThat(diary.summary()).isEqualTo("病院を中心に探索した一日。");
      assertThat(diary.tags()).containsExactly("探索", "病院");
      assertThat(diary.sourceType()).isEqualTo("AWS_BEDROCK_DIARY");
    });
    assertThat(timelinePostRepository.findAll()).singleElement().satisfies(post -> {
      assertThat(post.getPostType()).isEqualTo("DIARY");
      assertThat(post.getActorName()).isEqualTo("冒険記録局");
      assertThat(post.getLinkUrl()).isEqualTo("/diaries/2026-08-03");
      assertThat(post.getLinkLabel()).isEqualTo("日記を読む");
    });
  }
}
