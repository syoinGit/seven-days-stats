package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.entity.TimelinePostType;
import com.yuki.sevendays_states.repository.T_TimelinePostRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:survivor_karen;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.ai.enabled=true",
    "app.survivor-karen.image-enabled=false"
})
class SurvivorKarenPublishingServiceTests {

  @Autowired
  private SurvivorKarenPublishingService publishingService;

  @Autowired
  private T_TimelinePostRepository postRepository;

  @Autowired
  private TimelinePostService timelinePostService;

  @Test
  void publishesExactlyOneTextPostForTheDate() {
    LocalDate date = LocalDate.of(2026, 8, 9);

    var first = publishingService.publishIfMissing(date);
    var duplicate = publishingService.publishIfMissing(date);

    assertThat(first.status()).isEqualTo(SurvivorKarenPublishingService.PublishStatus.PUBLISHED);
    assertThat(duplicate.status())
        .isEqualTo(SurvivorKarenPublishingService.PublishStatus.ALREADY_PUBLISHED);
    assertThat(postRepository.findAll())
        .filteredOn(post -> TimelinePostType.SURVIVOR_KAREN.name().equals(post.getPostType()))
        .singleElement()
        .satisfies(post -> {
          assertThat(post.getActorName()).isEqualTo("サバイバーカレン");
          assertThat(post.getActorPlayerId()).isNull();
          assertThat(post.getSourceHash()).isEqualTo("SURVIVOR_KAREN:" + date);
          assertThat(post.getPostSubtype()).isNotBlank();
          assertThat(post.getBaseLikeCount()).isPositive();
          assertThat(post.getImageUrl()).isNull();
        });
    assertThat(timelinePostService.latestKarenImageDate()).isEmpty();
  }

  @Test
  void imageFailureFallsBackToTheDailyTextPost() {
    SurvivorKarenProperties properties = new SurvivorKarenProperties(
        true, 3, 12, "us-east-1", "amazon.nova-canvas-v1:0",
        "watchpoint-images", "watchpoint/posts/survivor-karen", "");
    TimelinePostService timeline = mock(TimelinePostService.class);
    ImageGenerationService images = mock(ImageGenerationService.class);
    LocalDate date = LocalDate.of(2026, 8, 12);
    when(timeline.existsBySourceHash(anyString())).thenReturn(false);
    when(timeline.latestKarenImageDate()).thenReturn(Optional.of(date.minusDays(10)));
    when(images.generateAndStore(anyString(), anyString(), anyString(), any(Long.class)))
        .thenThrow(new ImageGenerationService.ImageGenerationException("unavailable"));
    when(timeline.publishKaren(eq(date), any(OffsetDateTime.class), anyString(), anyString(),
        eq(""), anyInt())).thenReturn(true);
    SurvivorKarenPublishingService service = new SurvivorKarenPublishingService(
        aiProperties(true), properties, new KarenPostGenerator(), new KarenImagePromptGenerator(),
        new KarenPopularityService(), images, timeline);

    var result = service.publishIfMissing(
        date, OffsetDateTime.of(2026, 8, 12, 12, 0, 0, 0, ZoneOffset.ofHours(9)));

    assertThat(result.status()).isEqualTo(SurvivorKarenPublishingService.PublishStatus.PUBLISHED);
    assertThat(result.imageAttached()).isFalse();
    verify(timeline).publishKaren(eq(date), any(OffsetDateTime.class), anyString(), anyString(),
        eq(""), anyInt());
  }

  @Test
  void masterFlagDisablesManualKarenPublishingBeforeImageGeneration() {
    ImageGenerationService images = mock(ImageGenerationService.class);
    TimelinePostService timeline = mock(TimelinePostService.class);
    SurvivorKarenPublishingService service = new SurvivorKarenPublishingService(
        aiProperties(false), new SurvivorKarenProperties(true, 3, 12, "us-east-1", "model",
            "bucket", "prefix", ""),
        new KarenPostGenerator(), new KarenImagePromptGenerator(), new KarenPopularityService(),
        images, timeline);

    var result = service.publishIfMissing(LocalDate.of(2026, 8, 13));

    assertThat(result.status()).isEqualTo(SurvivorKarenPublishingService.PublishStatus.DISABLED);
    verify(images, org.mockito.Mockito.never()).generateAndStore(
        anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  private AiAnalysisProperties aiProperties(boolean enabled) {
    return new AiAnalysisProperties(enabled, 30, 60, "", "ap-northeast-1", "model", 240,
        java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1), 10);
  }
}
