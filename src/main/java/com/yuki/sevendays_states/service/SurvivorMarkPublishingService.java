package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.SplittableRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Publishes a sparse trail report from logs that are already at least two calendar days old. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SurvivorMarkPublishingService {

  private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

  private final SurvivorMarkProperties properties;
  private final SurvivorKarenProperties imageProperties;
  private final SurvivorMarkCandidateService candidateService;
  private final MarkPostGenerator postGenerator;
  private final MarkImagePromptGenerator promptGenerator;
  private final MarkPopularityService popularityService;
  private final ImageGenerationService imageGenerationService;
  private final TimelinePostService timelinePostService;

  public PublishResult publishIfDue() {
    OffsetDateTime now = OffsetDateTime.now(JAPAN);
    if (!enabled()) return new PublishResult(PublishStatus.DISABLED, now.toLocalDate(), null, false);
    if (now.getHour() < properties.postHour()) {
      return new PublishResult(PublishStatus.TOO_EARLY, now.toLocalDate(), null, false);
    }
    if (!due(now.toLocalDate())) return new PublishResult(PublishStatus.NOT_DUE, now.toLocalDate(), null, false);
    return publish(now.toLocalDate(), now);
  }

  /** Manual entry point: it still uses the historical window, but ignores the hour and interval. */
  public PublishResult publishTodayIfPossible() {
    OffsetDateTime now = OffsetDateTime.now(JAPAN);
    return enabled() ? publish(now.toLocalDate(), now)
        : new PublishResult(PublishStatus.DISABLED, now.toLocalDate(), null, false);
  }

  PublishResult publish(LocalDate date, OffsetDateTime publishedAt) {
    Optional<SurvivorMarkCandidateService.Candidate> candidate = candidateService.select(date, properties);
    if (candidate.isEmpty()) return new PublishResult(PublishStatus.NO_CANDIDATE, date, null, false);
    MarkPostGenerator.MarkPost post = postGenerator.generate(date, candidate.get());
    String imageUrl = "";
    if (shouldAttachImage(date)) {
      try {
        MarkImagePromptGenerator.ImagePrompt prompt = promptGenerator.prompt(post);
        imageUrl = imageGenerationService.generateAndStore(prompt.text(), prompt.negativeText(),
            imageProperties.imagePrefix() + "/survivor-mark/" + date + "-" + candidate.get().key() + ".png",
            post.imageSeed());
      } catch (RuntimeException exception) {
        log.warn("Survivor Mark image generation failed; publishing text only. date={}, candidate={}",
            date, candidate.get().key(), exception);
      }
    }
    boolean attached = !imageUrl.isBlank();
    boolean published = timelinePostService.publishMark(date, publishedAt, post.body(),
        candidate.get().coordinate(), post.subtype(), candidate.get().key(), imageUrl,
        popularityService.baseLikes(candidate.get(), attached, date));
    return new PublishResult(published ? PublishStatus.PUBLISHED : PublishStatus.ALREADY_PUBLISHED,
        date, candidate.get(), attached);
  }

  private boolean enabled() { return properties.enabled() && properties.postEnabled(); }

  private boolean due(LocalDate date) {
    return timelinePostService.latestMarkPostDate()
        .map(last -> ChronoUnit.DAYS.between(last, date) >= properties.postIntervalDays())
        .orElse(true);
  }

  private boolean shouldAttachImage(LocalDate date) {
    if (!properties.imageEnabled() || !imageProperties.imageConfigured()) return false;
    return timelinePostService.latestMarkImageDate().map(last -> {
      SplittableRandom random = new SplittableRandom(last.toEpochDay() ^ 0x494d4147L);
      int interval = Math.max(2, properties.imageIntervalDays() + random.nextInt(-1, 2));
      return ChronoUnit.DAYS.between(last, date) >= interval;
    }).orElseGet(() -> Math.floorMod(date.toEpochDay(), properties.imageIntervalDays()) == 0);
  }

  public enum PublishStatus { PUBLISHED, ALREADY_PUBLISHED, TOO_EARLY, NOT_DUE, NO_CANDIDATE, DISABLED }
  public record PublishResult(PublishStatus status, LocalDate date,
                              SurvivorMarkCandidateService.Candidate candidate, boolean imageAttached) { }
}
