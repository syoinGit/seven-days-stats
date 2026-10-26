package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import com.yuki.rubinco.watchpoint.ai.entity.AiPostType;
import com.yuki.rubinco.watchpoint.ingest.SevenDaysTelnetCommandClient;
import com.yuki.rubinco.watchpoint.readmodel.WatchpointAiObservationService;
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
  private static final String GAME_ID = "7dtd";

  private final AiAnalysisProperties properties;
  private final WatchpointAiObservationService observationService;
  private final BedrockWatchpointClient bedrockClient;
  private final AiCommentService aiCommentService;
  private final SevenDaysTelnetCommandClient telnetCommandClient;
  private final WatchpointAiStateService stateService;
  private final ServerActivityGate gate;

  /**
   * The 7DTD turn of the AI's murmur (AiMurmurService decides when and whether 7DTD speaks): one ordinary post from the
   * last window of the server log, or nothing when the window has no activity.
   */
  public PublishResult publishMurmur() {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    // Base rule: no AI posts while the server is not running, manual tests included.
    if (!gate.aiAllowed(GAME_ID)) {
      return new PublishResult(PublishStatus.SERVER_STOPPED, null);
    }
    WatchpointAiObservationService.AnalysisRequest request = observationService.buildRequest();
    if (!hasActivity(request)) {
      return new PublishResult(PublishStatus.NO_ACTIVITY, null);
    }
    return publishSafely(request, AiPostType.NORMAL);
  }

  public PublishResult publishNow() {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    // Base rule: no AI posts while the server is not running, manual tests included.
    if (!gate.aiAllowed(GAME_ID)) {
      return new PublishResult(PublishStatus.SERVER_STOPPED, null);
    }
    return publishSafely(observationService.buildRequest(), AiPostType.NORMAL);
  }

  public PublishResult publishNow(AiPostType postType) {
    if (!properties.enabled()) {
      return new PublishResult(PublishStatus.DISABLED, null);
    }
    // Base rule: no AI posts while the server is not running, manual tests included.
    if (!gate.aiAllowed(GAME_ID)) {
      return new PublishResult(PublishStatus.SERVER_STOPPED, null);
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
        // Use the same server-wide say path as player status notifications.
        boolean broadcasted = telnetCommandClient.broadcast(generated.body());
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

  private boolean hasActivity(WatchpointAiObservationService.AnalysisRequest request) {
    var observation = request.observation();
    if (observation == null) {
      return false;
    }
    if (observation.currentTotals() == null) {
      return !observation.events().isEmpty();
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
    NO_ACTIVITY,
    FAILED,
    DISABLED,
    SERVER_STOPPED
  }

  public record PublishResult(PublishStatus status, AiCommentService.AiCommentEntry comment) {
  }
}
