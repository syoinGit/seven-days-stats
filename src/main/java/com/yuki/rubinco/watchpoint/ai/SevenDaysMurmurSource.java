package com.yuki.rubinco.watchpoint.ai;

import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import com.yuki.rubinco.watchpoint.persistence.repository.T_PlayerCurrentStateRepository;
import com.yuki.rubinco.worldprogress.ai.AiMurmurSource;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 7DTD in the AI's murmur: connected players from the log import, the post from the WATCHPOINT pipeline. */
@Component
@RequiredArgsConstructor
public class SevenDaysMurmurSource implements AiMurmurSource {
  private final AiAnalysisProperties properties;
  private final T_PlayerCurrentStateRepository players;
  private final AiCommentService comments;
  private final WatchpointAiPublishingService publishing;

  @Override
  public String gameId() {
    return "7dtd";
  }

  @Override
  public boolean enabled() {
    return properties.enabled();
  }

  @Override
  public int onlineCount() {
    return players.findByOnlineTrue().size();
  }

  @Override
  public Optional<OffsetDateTime> lastPublished() {
    return comments.latestBySourceType(WatchpointAiPublishingService.SOURCE_TYPE).map(AiCommentService.AiCommentEntry::publishedAt);
  }

  @Override
  public Outcome murmur() {
    return switch (publishing.publishMurmur().status()) {
      case PUBLISHED -> Outcome.PUBLISHED;
      case FAILED -> Outcome.FAILED;
      default -> Outcome.NO_DATA;
    };
  }
}
