package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class KarenPostGeneratorTests {

  private final KarenPostGenerator generator = new KarenPostGenerator();
  private final KarenPopularityService popularity = new KarenPopularityService();
  private final AiAgentProfileService profiles = mock(AiAgentProfileService.class);
  private final KarenImagePromptGenerator promptGenerator = promptGenerator();

  private KarenImagePromptGenerator promptGenerator() {
    when(profiles.personalityPrompt(AiAgentProfileService.KAREN)).thenReturn("明るく行動的なKaren");
    return new KarenImagePromptGenerator(profiles);
  }

  @Test
  void createsVariedShortPostsWithoutServerData() {
    LocalDate start = LocalDate.of(2026, 1, 1);
    var posts = IntStream.range(0, 365)
        .mapToObj(start::plusDays)
        .map(generator::generate)
        .toList();

    assertThat(posts)
        .allSatisfy(post -> assertThat(post.body().codePointCount(0, post.body().length()))
            .isLessThanOrEqualTo(TimelinePostService.MAX_POST_CHARACTERS));
    assertThat(posts.stream().map(KarenPostGenerator.KarenPost::body).distinct().count())
        .isGreaterThan(60);
    Set<KarenPostTheme> themes = posts.stream()
        .map(KarenPostGenerator.KarenPost::theme)
        .collect(Collectors.toSet());
    assertThat(themes).containsAll(Set.of(KarenPostTheme.values()));
  }

  @Test
  void imagePromptKeepsKarenAppearanceAndGameScreenshotStyle() {
    var prompt = promptGenerator.prompt(generator.generate(LocalDate.of(2026, 8, 9)));

    assertThat(prompt.text())
        .contains("same young adult woman named Karen", "blonde hair in a high ponytail",
            "red bandana", "no HUD", "zombie survival game screenshot");
    assertThat(prompt.text()).hasSizeLessThanOrEqualTo(1_024);
    assertThat(prompt.negativeText()).contains("anime", "explicit nudity", "different hair color");
  }

  @Test
  void imagePostsAlwaysReceiveAPopularityBoost() {
    LocalDate date = LocalDate.of(2026, 8, 9);
    for (KarenPostTheme theme : KarenPostTheme.values()) {
      int textLikes = popularity.baseLikes(theme, false, date);
      int imageLikes = popularity.baseLikes(theme, true, date);
      assertThat(textLikes).isBetween(theme.minimumLikes(), theme.maximumLikes());
      assertThat(imageLikes).isGreaterThan(textLikes);
    }
  }
}
