package com.yuki.rubinco.watchpoint.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.timeline.service.TimelinePostService;
import com.yuki.rubinco.watchpoint.ai.entity.AiPostType;
import com.yuki.rubinco.watchpoint.ai.repository.T_AiCommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rubinco_ai_comments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
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
  void rejectsBlankComment() {
    assertThatThrownBy(() -> service.publishGenerated("", "", "AWS_BEDROCK"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void publishesGeneratedObservation() {
    service.publishGenerated("WATCHPOINT観測記録", "静かな探索が続いています。", "AWS_BEDROCK");

    assertThat(repository.count()).isOne();
    assertThat(service.latestComment()).get().satisfies(comment -> {
      assertThat(comment.diaryDate()).isNull();
      assertThat(comment.body()).isEqualTo("静かな探索が続いています。");
      assertThat(comment.sourceType()).isEqualTo("AWS_BEDROCK");
    });
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
          assertThat(post.getActorName()).isEqualTo("WATCHPOINT");
          assertThat(post.getPostType()).isEqualTo("PLAYER_ANALYSIS");
        });
  }
}
