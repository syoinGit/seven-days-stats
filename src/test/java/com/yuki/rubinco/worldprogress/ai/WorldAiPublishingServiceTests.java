package com.yuki.rubinco.worldprogress.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.watchpoint.ai.AiAgentProfileService;
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
  private final BedrockTextClient bedrock = mock(BedrockTextClient.class);
  private final T_TimelinePostRepository timeline = mock(T_TimelinePostRepository.class);
  private final M_GameWorldRepository worlds = mock(M_GameWorldRepository.class);
  private final AiAgentProfileService agents = mock(AiAgentProfileService.class);
  private final WorldAiFacts site = new WorldAiFacts() {
    @Override public String gameId() { return "corekeeper"; }
    @Override public String gameTitle() { return "Core Keeper"; }
    @Override public int onlineCount() { return 2; }
    @Override public Optional<String> facts() { return Optional.of("保管アイテム: 12種"); }
  };

  private WorldAiPublishingService service(boolean enabled) {
    return new WorldAiPublishingService(new WorldAiProperties(enabled, Duration.ofHours(3), 300),
        List.of(site), bedrock, agents, timeline, worlds);
  }

  @Test
  void eachSiteIsAMurmurSourceThatPostsWhenPicked() {
    var source = service(false).sources().getFirst();
    assertThat(source.gameId()).isEqualTo("corekeeper");
    assertThat(source.enabled()).isFalse();
    assertThat(service(true).sources().getFirst().enabled()).isTrue();
    assertThat(source.onlineCount()).isEqualTo(2);
    T_TimelinePost recent = new T_TimelinePost();
    recent.setPublishedAt(OffsetDateTime.now().minusHours(1));
    doReturn(Optional.of(recent)).when(timeline)
        .findTopByGameIdAndPostTypeOrderByPublishedAtDesc("corekeeper", "WORLD_AI");
    assertThat(source.lastPublished()).contains(recent.getPublishedAt());

    M_GameWorld world = new M_GameWorld();
    world.setPublished(true);
    doReturn(Optional.of(world)).when(worlds).findFirstByGameIdAndActiveTrue("corekeeper");
    doReturn("統計を覗ける人").when(agents).personalityPrompt("WATCHPOINT");
    doReturn("「宝箱がにぎやかになってきたかも。」").when(bedrock)
        .generate(anyString(), anyString(), anyInt());
    assertThat(service(true).sources().getFirst().murmur()).isEqualTo(AiMurmurSource.Outcome.PUBLISHED);
    ArgumentCaptor<T_TimelinePost> saved = ArgumentCaptor.forClass(T_TimelinePost.class);
    verify(timeline).save(saved.capture());
    assertThat(saved.getValue().getGameId()).isEqualTo("corekeeper");
    assertThat(saved.getValue().getMessage()).isEqualTo("宝箱がにぎやかになってきたかも。");
    assertThat(saved.getValue().isVisible()).isTrue();
    // One voice on every site: the same character and personality as the 7DTD posts.
    assertThat(saved.getValue().getActorName()).isEqualTo("WATCHPOINT");
    ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
    verify(bedrock).generate(system.capture(), anyString(), anyInt());
    assertThat(system.getValue()).startsWith("統計を覗ける人").contains("Core Keeper", "120文字以内", "丁寧語", "最近の変化");
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
    doReturn("").when(bedrock).generate(anyString(), anyString(), anyInt());
    assertThat(service(true).publish(site)).isEqualTo(AiMurmurSource.Outcome.FAILED);
    verify(timeline, never()).save(any());
  }
}
