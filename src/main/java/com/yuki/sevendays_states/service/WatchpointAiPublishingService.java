package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.AiAnalysisProperties;
import com.yuki.sevendays_states.entity.AiPostType;
import com.yuki.sevendays_states.web.WatchpointAiObservationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Coordinates observation, generation, validation, and persistence without holding a DB transaction during AWS I/O. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchpointAiPublishingService {

  public static final String SOURCE_TYPE = "AWS_BEDROCK";
  private static final String TITLE = "WATCHPOINT観測記録";

  private final AiAnalysisProperties properties;
  private final WatchpointAiObservationService observationService;
  private final BedrockWatchpointClient bedrockClient;
  private final AiCommentService aiCommentService;
  private final SevenDaysTelnetCommandClient telnetCommandClient;
  private final WatchpointAiStateService stateService;

  public PublishResult publishIfDue() {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC)
        .minus(properties.scheduleInterval());
    boolean recentlyPublished = aiCommentService.latestBySourceType(SOURCE_TYPE)
        .map(comment -> !comment.publishedAt().isBefore(threshold))
        .orElse(false);
    if (recentlyPublished) {
      return new PublishResult(PublishStatus.NOT_DUE, null);
    }
    OffsetDateTime dayStart = startOfToday();
    long todayCount = aiCommentService.generatedTimelineCountSince(dayStart);
    if (todayCount >= properties.maxPostsPerDay()) {
      return new PublishResult(PublishStatus.DAILY_LIMIT, null);
    }
    AiPostType postType = nextPostType(todayCount, dayStart);
    WatchpointAiObservationService.AnalysisRequest request = postType == AiPostType.NORMAL
        ? observationService.buildRequest()
        : observationService.buildRequest(postType, (int) todayCount);
    if (!hasActivity(request)) {
      return new PublishResult(PublishStatus.NO_ACTIVITY, null);
    }
    if (postType == AiPostType.PLAYER_ANALYSIS
        && request.observation().survivors().isEmpty()) {
      return new PublishResult(PublishStatus.NO_ACTIVITY, null);
    }
    return publishSafely(request, postType);
  }

  public PublishResult publishNow() {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    return publishSafely(observationService.buildRequest(), AiPostType.NORMAL);
  }

  public PublishResult publishNow(AiPostType postType) {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    return publishSafely(observationService.buildRequest(postType, 0), postType);
  }

  private PublishResult publishSafely(
      WatchpointAiObservationService.AnalysisRequest request, AiPostType postType) {
    try {
      BedrockWatchpointClient.GeneratedPost generated = bedrockClient.generate(request);
      AiCommentService.AiCommentEntry saved = postType == AiPostType.NORMAL
          ? aiCommentService.publishGenerated(TITLE, generated.body(), SOURCE_TYPE)
          : aiCommentService.publishGenerated(TITLE, generated.body(), SOURCE_TYPE, postType, null);
      try {
        stateService.recordPublished(saved, postType, request);
      } catch (RuntimeException stateException) {
        log.error(
            "WATCHPOINT {} post was saved, but memory/emotion update failed. commentId={}",
            postType,
            saved.id(),
            stateException);
      }
      if (postType.isGameBroadcastEnabled()) {
        // Use the same server-wide say path as player status notifications. Keep the in-game
        // label independent from the WATCHPOINT identifier used by the web timeline/database.
        boolean broadcasted = telnetCommandClient.broadcast("観測AI " + saved.body());
        if (!broadcasted) {
          log.warn(
              "WATCHPOINT {} post was saved, but its in-game Telnet broadcast failed. commentId={}",
              postType,
              saved.id());
        } else {
          log.info("WATCHPOINT {} post broadcast to 7DTD via Telnet. commentId={}",
              postType, saved.id());
        }
      }
      return new PublishResult(PublishStatus.PUBLISHED, saved);
    } catch (RuntimeException exception) {
      log.error("WATCHPOINT {} generation failed; this cycle is skipped.", postType, exception);
      return new PublishResult(PublishStatus.FAILED, null);
    }
  }

  private AiPostType nextPostType(long todayCount, OffsetDateTime dayStart) {
    int localHour = OffsetDateTime.now(ZoneId.of("Asia/Tokyo")).getHour();
    if (localHour >= 23 && !aiCommentService.hasPostTypeSince(AiPostType.DAILY_SUMMARY, dayStart)) {
      return AiPostType.DAILY_SUMMARY;
    }
    if (todayCount > 0 && todayCount % 3 == 2) {
      return (todayCount / 3) % 2 == 0
          ? AiPostType.PLAYER_ANALYSIS : AiPostType.SERVER_ANALYSIS;
    }
    return AiPostType.NORMAL;
  }

  private OffsetDateTime startOfToday() {
    ZoneId tokyo = ZoneId.of("Asia/Tokyo");
    return LocalDate.now(tokyo).atStartOfDay(tokyo).toOffsetDateTime();
  }

  private boolean hasActivity(WatchpointAiObservationService.AnalysisRequest request) {
    var observation = request.observation();
    if (observation == null || observation.currentTotals() == null) {
      return false;
    }
    var totals = observation.currentTotals();
    return !observation.events().isEmpty()
        || totals.joins() > 0 || totals.leaves() > 0 || totals.kills() > 0
        || totals.sleeperEncounters() > 0 || totals.deaths() > 0 || totals.hordeEvents() > 0
        || (totals.onFootDistanceMeters() != null && totals.onFootDistanceMeters().signum() > 0)
        || (totals.vehicleDistanceMeters() != null && totals.vehicleDistanceMeters().signum() > 0);
  }

  public enum PublishStatus {
    PUBLISHED,
    NOT_DUE,
    NO_ACTIVITY,
    DAILY_LIMIT,
    FAILED,
    DISABLED
  }

  public record PublishResult(PublishStatus status, AiCommentService.AiCommentEntry comment) {
  }
}
