package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

  private final AiAnalysisProperties aiProperties;
  private final SurvivorMarkProperties properties;
  private final SurvivorMarkCandidateService candidateService;
  private final BedrockMarkClient bedrockClient;
  private final MarkPopularityService popularityService;
  private final TimelinePostService timelinePostService;

  public PublishResult publishIfDue() {
    OffsetDateTime now = OffsetDateTime.now(JAPAN);
    if (!enabled()) return new PublishResult(PublishStatus.DISABLED, now.toLocalDate(), null);
    if (now.isBefore(postAt(now.toLocalDate()))) {
      return new PublishResult(PublishStatus.TOO_EARLY, now.toLocalDate(), null);
    }
    if (!due(now.toLocalDate())) return new PublishResult(PublishStatus.NOT_DUE, now.toLocalDate(), null);
    return publish(now.toLocalDate(), now);
  }

  /** Manual entry point: it still uses the historical window, but ignores the hour and interval. */
  public PublishResult publishTodayIfPossible() {
    OffsetDateTime now = OffsetDateTime.now(JAPAN);
    return enabled() ? publish(now.toLocalDate(), now)
        : new PublishResult(PublishStatus.DISABLED, now.toLocalDate(), null);
  }

  PublishResult publish(LocalDate date, OffsetDateTime publishedAt) {
    Optional<SurvivorMarkCandidateService.Candidate> candidate = candidateService.select(date, properties);
    if (candidate.isEmpty()) return new PublishResult(PublishStatus.NO_CANDIDATE, date, null);
    String sourceHash = "SURVIVOR_MARK:" + date + ":" + candidate.get().key();
    if (timelinePostService.existsBySourceHash(sourceHash)) {
      return new PublishResult(PublishStatus.ALREADY_PUBLISHED, date, candidate.get());
    }
    BedrockMarkClient.GeneratedMarkPost post;
    try {
      post = bedrockClient.generate(candidate.get());
    } catch (RuntimeException exception) {
      log.warn("Survivor Mark Bedrock generation failed; no fallback post was published. date={}, candidate={}",
          date, candidate.get().key(), exception);
      return new PublishResult(PublishStatus.FAILED, date, candidate.get());
    }
    boolean published = timelinePostService.publishMark(date, publishedAt, post.body(),
        candidate.get().coordinate(), subtype(candidate.get()), candidate.get().key(), "",
        popularityService.baseLikes(candidate.get(), date));
    return new PublishResult(published ? PublishStatus.PUBLISHED : PublishStatus.ALREADY_PUBLISHED,
        date, candidate.get());
  }

  private boolean enabled() { return aiProperties.enabled(); }

  private boolean due(LocalDate date) {
    return timelinePostService.latestMarkPostDate()
        .map(last -> last.isBefore(date))
        .orElse(true);
  }

  private String subtype(SurvivorMarkCandidateService.Candidate candidate) {
    return candidate.kills() >= 3 || !candidate.zombieTypes().isEmpty() ? "HAZARD" : "TRAIL";
  }

  /** Mark gets a different stable slot inside the nightly server uptime window each day. */
  private OffsetDateTime postAt(LocalDate date) {
    SplittableRandom random = new SplittableRandom(date.toEpochDay() ^ 0x4d41524b54494dL);
    int latestOffsetMinutes = Math.max(0, (23 - properties.postHour()) * 60 + 10);
    return date.atTime(properties.postHour(), 10).atZone(JAPAN).toOffsetDateTime()
        .plusMinutes(random.nextInt(latestOffsetMinutes + 1));
  }

  public enum PublishStatus { PUBLISHED, ALREADY_PUBLISHED, TOO_EARLY, NOT_DUE, NO_CANDIDATE, FAILED, DISABLED }
  public record PublishResult(PublishStatus status, LocalDate date,
                              SurvivorMarkCandidateService.Candidate candidate) { }
}
