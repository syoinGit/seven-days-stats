package com.yuki.rubinco.worldprogress.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.watchpoint.ai.BedrockTextClient;
import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorldAiPublishingServiceTests {
  private final ServerActivityGate gate = mock(ServerActivityGate.class);
  private final BedrockTextClient bedrock = mock(BedrockTextClient.class);
  private final T_TimelinePostRepository timeline = mock(T_TimelinePostRepository.class);
  private final M_GameWorldRepository worlds = mock(M_GameWorldRepository.class);
  private final WorldAiFacts site = new WorldAiFacts() {
    @Override public String gameId() { return "corekeeper"; }
    @Override public String actorName() { return "Octarine観測AI"; }
    @Override public Optional<String> facts() { return Optional.of("保管アイテム: 12種"); }
  };

  private WorldAiPublishingService service(boolean enabled) {
    return new WorldAiPublishingService(new WorldAiProperties(enabled, Duration.ofHours(3), 300),
        List.of(site), gate, bedrock, timeline, worlds);
  }

  @Test
  void postsOnlyWhileTheServerIsOnlineAndOncePerInterval() {
    assertThat(service(false).publishIfDue(site).status()).isEqualTo(WorldAiPublishingService.Status.DISABLED);

    doReturn(false).when(gate).aiAllowed("corekeeper");
    assertThat(service(true).publishIfDue(site).status())
        .isEqualTo(WorldAiPublishingService.Status.SERVER_STOPPED);
    verify(bedrock, never()).generate(anyString(), anyString(), anyInt(), anyFloat());

    doReturn(true).when(gate).aiAllowed("corekeeper");
    T_TimelinePost recent = new T_TimelinePost();
    recent.setPublishedAt(OffsetDateTime.now().minusHours(1));
    doReturn(Optional.of(recent)).when(timeline)
        .findTopByGameIdAndPostTypeOrderByPublishedAtDesc("corekeeper", "WORLD_AI");
    assertThat(service(true).publishIfDue(site).status()).isEqualTo(WorldAiPublishingService.Status.NOT_DUE);

    recent.setPublishedAt(OffsetDateTime.now().minusHours(4));
    M_GameWorld world = new M_GameWorld();
    world.setPublished(true);
    doReturn(Optional.of(world)).when(worlds).findFirstByGameIdAndActiveTrue("corekeeper");
    doReturn("「宝箱がにぎやかになってきたかも。」").when(bedrock)
        .generate(anyString(), anyString(), anyInt(), anyFloat());
    assertThat(service(true).publishIfDue(site).status()).isEqualTo(WorldAiPublishingService.Status.PUBLISHED);
    ArgumentCaptor<T_TimelinePost> saved = ArgumentCaptor.forClass(T_TimelinePost.class);
    verify(timeline).save(saved.capture());
    assertThat(saved.getValue().getGameId()).isEqualTo("corekeeper");
    assertThat(saved.getValue().getMessage()).isEqualTo("宝箱がにぎやかになってきたかも。");
    assertThat(saved.getValue().isVisible()).isTrue();
  }

  @Test
  void rejectsEmptyOrLinkedOutputAndShortensLongOutput() {
    assertThatThrownBy(() -> WorldAiPublishingService.clean(" ")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> WorldAiPublishingService.clean("見て https://example.com"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(WorldAiPublishingService.clean("あ".repeat(400))).hasSize(WorldAiPublishingService.MAX_BODY);
  }

  @Test
  void aFailedGenerationIsReportedAndNothingIsSaved() {
    doReturn(true).when(gate).aiAllowed("corekeeper");
    doReturn(Optional.empty()).when(timeline).findTopByGameIdAndPostTypeOrderByPublishedAtDesc(any(), any());
    doReturn("").when(bedrock).generate(anyString(), anyString(), anyInt(), anyFloat());
    assertThat(service(true).publishIfDue(site).status()).isEqualTo(WorldAiPublishingService.Status.FAILED);
    verify(timeline, never()).save(any());
  }
}
