package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SurvivorKarenProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.SplittableRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Publishes one fictional Karen post per local day without consulting real game activity. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SurvivorKarenPublishingService {

  private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

  private final SurvivorKarenProperties properties;
  private final KarenPostGenerator postGenerator;
  private final KarenImagePromptGenerator promptGenerator;
  private final KarenPopularityService popularityService;
  private final ImageGenerationService imageGenerationService;
  private final TimelinePostService timelinePostService;

  public PublishResult publishIfDue() {
    OffsetDateTime now = OffsetDateTime.now(JAPAN);
    if (!properties.enabled() || !properties.postEnabled()) {
      return new PublishResult(PublishStatus.DISABLED, now.toLocalDate(), null, false);
    }
    if (now.isBefore(postAt(now.toLocalDate()))) {
      return new PublishResult(PublishStatus.TOO_EARLY, now.toLocalDate(), null, false);
    }
    return publishIfMissing(now.toLocalDate(), now);
  }

  public PublishResult publishIfMissing(LocalDate date) {
    OffsetDateTime now = OffsetDateTime.now(JAPAN);
    return publishIfMissing(date, now);
  }

  /** Manual maintenance entry point that ignores only the configured posting hour. */
  public PublishResult publishTodayIfMissing() {
    OffsetDateTime now = OffsetDateTime.now(JAPAN);
    return publishIfMissing(now.toLocalDate(), now);
  }

  PublishResult publishIfMissing(LocalDate date, OffsetDateTime publishedAt) {
    if (!properties.enabled() || !properties.postEnabled()) {
      return new PublishResult(PublishStatus.DISABLED, date, null, false);
    }
    String sourceHash = sourceHash(date);
    if (timelinePostService.existsBySourceHash(sourceHash)) {
      return new PublishResult(PublishStatus.ALREADY_PUBLISHED, date, null, false);
    }

    KarenPostGenerator.KarenPost post = postGenerator.generate(date);
    String imageUrl = "";
    if (shouldAttachImage(date)) {
      try {
        KarenImagePromptGenerator.ImagePrompt prompt = promptGenerator.prompt(post);
        imageUrl = imageGenerationService.generateAndStore(
            prompt.text(), prompt.negativeText(), objectKey(date, post.theme()), post.imageSeed());
      } catch (RuntimeException exception) {
        log.warn("Karen image generation failed; publishing text only. date={}, theme={}",
            date, post.theme(), exception);
      }
    }

    boolean hasImage = !imageUrl.isBlank();
    int baseLikes = popularityService.baseLikes(post.theme(), hasImage, date);
    boolean published = timelinePostService.publishKaren(
        date, publishedAt, post.body(), post.theme().name(), imageUrl, baseLikes);
    return new PublishResult(
        published ? PublishStatus.PUBLISHED : PublishStatus.ALREADY_PUBLISHED,
        date, post.theme(), hasImage);
  }

  private boolean shouldAttachImage(LocalDate date) {
    if (!properties.imageConfigured()) return false;
    int configuredInterval = Math.max(2, properties.imageIntervalDays());
    return timelinePostService.latestKarenImageDate()
        .map(lastImageDate -> {
          SplittableRandom random = KarenPostGenerator.random(lastImageDate, 0x494d414745L);
          int interval = Math.max(2, configuredInterval + random.nextInt(-1, 2));
          return ChronoUnit.DAYS.between(lastImageDate, date) >= interval;
        })
        .orElseGet(() -> Math.floorMod(date.toEpochDay(), configuredInterval) == 0);
  }

  /** A stable daily offset looks natural while surviving restarts without a second post. */
  private OffsetDateTime postAt(LocalDate date) {
    SplittableRandom random = KarenPostGenerator.random(date, 0x4b4152454e54494dL);
    int latestOffsetMinutes = Math.max(0, (23 - properties.postHour()) * 60 + 10);
    return date.atTime(properties.postHour(), 10).atZone(JAPAN).toOffsetDateTime()
        .plusMinutes(random.nextInt(latestOffsetMinutes + 1));
  }

  private String objectKey(LocalDate date, KarenPostTheme theme) {
    return properties.imagePrefix() + "/" + date + "-" + theme.name().toLowerCase() + ".png";
  }

  static String sourceHash(LocalDate date) {
    return "SURVIVOR_KAREN:" + date;
  }

  public enum PublishStatus {
    PUBLISHED,
    ALREADY_PUBLISHED,
    TOO_EARLY,
    DISABLED
  }

  public record PublishResult(
      PublishStatus status, LocalDate date, KarenPostTheme theme, boolean imageAttached) { }
}
