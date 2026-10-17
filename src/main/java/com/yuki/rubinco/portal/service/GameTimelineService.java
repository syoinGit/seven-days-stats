package com.yuki.rubinco.portal.service;

import com.yuki.rubinco.portal.entity.M_GamePortalState;
import com.yuki.rubinco.portal.repository.M_GamePortalStateRepository;
import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class GameTimelineService {
  private static final int MAX_PAGE_SIZE = 50;
  private static final int MAX_CREATOR_SUB_LENGTH = 80;
  private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() { };

  private final GamePortalService portalService;
  private final T_TimelinePostRepository timelineRepository;
  private final M_GamePortalStateRepository stateRepository;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public List<TimelineEventView> timeline(String gameId, int page, int size) {
    requireGame(gameId);
    int safePage = Math.max(0, page);
    int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    if ((long) safePage * safeSize > Integer.MAX_VALUE) {
      return List.of();
    }
    return timelineRepository.findAllByGameIdAndVisibleTrueOrderByPublishedAtDescIdDesc(
            gameId, PageRequest.of(safePage, safeSize))
        .stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public Page<TimelineEventView> sharedTimeline(
      List<String> gameIds, int page) {
    return timelineRepository.findAllByGameIdInAndVisibleTrueOrderByPublishedAtDescIdDesc(
        gameIds, PageRequest.of(Math.max(0, Math.min(page, 1000000)), 20)).map(this::view);
  }

  @Transactional(readOnly = true)
  public TimelineEventView latest(String gameId) {
    requireGame(gameId);
    return timelineRepository.findTopByGameIdAndVisibleTrueOrderByPublishedAtDescIdDesc(gameId)
        .map(this::view).orElse(null);
  }

  @Transactional
  public TimelineEventView create(
      String gameId, EventInput input, Authentication authentication) {
    requireGame(gameId);
    M_GamePortalState state = lockState(gameId);
    String eventId = UUID.randomUUID().toString();
    T_TimelinePost post = new T_TimelinePost();
    post.setGameId(gameId);
    post.setEventId(eventId);
    apply(post, input, authentication, true);
    post.setActorType("MEMBER");
    post.setSourceType("PORTAL_EVENT");
    post.setSourceHash("PORTAL_EVENT:" + eventId);
    post.setPriority(100);
    post.setVisible(true);
    T_TimelinePost saved = timelineRepository.save(post);
    refreshCurrentProgress(gameId, state);
    return view(saved);
  }

  @Transactional
  public TimelineEventView update(
      String gameId, String eventId, EventInput input, Authentication authentication) {
    requireGame(gameId);
    M_GamePortalState state = lockState(gameId);
    T_TimelinePost post = timelineRepository.findByEventIdAndGameId(eventId, gameId)
        .filter(value -> "PORTAL_EVENT".equals(value.getSourceType()))
        .orElseThrow(() -> new NoSuchElementException("編集可能なイベントが見つかりません。"));
    apply(post, input, authentication, false);
    T_TimelinePost saved = timelineRepository.save(post);
    refreshCurrentProgress(gameId, state);
    return view(saved);
  }

  private void apply(
      T_TimelinePost post, EventInput input, Authentication authentication, boolean newEvent) {
    post.setPostType(input.eventType().strip().toUpperCase(Locale.ROOT));
    post.setTitle(input.title().strip());
    post.setMessage(input.body().strip());
    if (newEvent || input.occurredAt() != null) {
      post.setPublishedAt(input.occurredAt() == null ? OffsetDateTime.now() : input.occurredAt());
    }
    post.setProgress(input.progress());
    post.setStageName(blankToNull(input.stageName()));
    if (newEvent) {
      post.setCreatorSub(subject(authentication));
    }
    post.setMetadataJson(json(input.metadata()));
    post.setActorName(actorName(authentication));
  }

  private M_GamePortalState lockState(String gameId) {
    // Serialize event projections and start requests through the same row until commit.
    return stateRepository.findForUpdate(gameId)
        .orElseThrow(() -> new IllegalStateException(
            "Game portal state is missing for configured game: " + gameId));
  }

  private void refreshCurrentProgress(String gameId, M_GamePortalState state) {
    var defaults = portalService.configuredGame(gameId);
    state.setProgress(timelineRepository
        .findTopByGameIdAndVisibleTrueAndProgressIsNotNullOrderByPublishedAtDescIdDesc(gameId)
        .map(T_TimelinePost::getProgress).orElse(defaults.getProgress()));
    state.setStageName(timelineRepository
        .findTopByGameIdAndVisibleTrueAndStageNameIsNotNullOrderByPublishedAtDescIdDesc(gameId)
        .map(T_TimelinePost::getStageName).orElse(defaults.getStage()));
    stateRepository.save(state);
  }

  private TimelineEventView view(T_TimelinePost post) {
    String eventId = post.getEventId() == null ? post.getSourceHash() : post.getEventId();
    String title = post.getTitle() == null ? post.getActorName() : post.getTitle();
    return new TimelineEventView(post.getGameId(), eventId, post.getPostType(),
        title, post.getMessage(), post.getPublishedAt(), post.getProgress(),
        post.getStageName(), post.getCreatorSub(), parseJson(post.getMetadataJson()),
        post.getCreatedAt(), post.getUpdatedAt(), post.getActorPlayerId(), post.getActorName());
  }

  private Map<String, Object> parseJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, METADATA_TYPE);
    } catch (Exception exception) {
      return Map.of("legacy", json);
    }
  }

  private String json(Map<String, Object> metadata) {
    String json;
    try {
      json = objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
    } catch (Exception exception) {
      throw new IllegalArgumentException("metadataをJSONへ変換できません。", exception);
    }
    if (json.length() > 4000) {
      throw new IllegalArgumentException("metadataは4000文字以内です。");
    }
    return json;
  }

  private void requireGame(String gameId) {
    if (!portalService.supports(gameId)) {
      throw new IllegalArgumentException("Unknown gameId: " + gameId);
    }
  }

  private String subject(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return boundedSubject(jwt.getToken().getSubject());
    }
    if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc) {
      return boundedSubject(oidc.getSubject());
    }
    return boundedSubject(
        "local:" + (authentication == null ? "unknown" : authentication.getName()));
  }

  private String boundedSubject(String value) {
    String subject = value == null || value.isBlank() ? "unknown" : value.strip();
    return subject.substring(0, Math.min(subject.length(), MAX_CREATOR_SUB_LENGTH));
  }

  private String actorName(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc) {
      if (oidc.getPreferredUsername() != null && !oidc.getPreferredUsername().isBlank()) {
        return oidc.getPreferredUsername().strip();
      }
      if (oidc.getEmail() != null && !oidc.getEmail().isBlank()) {
        return oidc.getEmail().strip();
      }
    }
    return authentication == null ? "unknown" : authentication.getName();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static Map<String, Object> immutableMetadata(Map<String, Object> metadata) {
    return metadata == null || metadata.isEmpty()
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  public record EventInput(
      String eventType, String title, String body, OffsetDateTime occurredAt,
      Integer progress, String stageName, Map<String, Object> metadata) {
    public EventInput {
      metadata = immutableMetadata(metadata);
    }
  }

  public record TimelineEventView(
      String gameId, String eventId, String eventType, String title, String body,
      OffsetDateTime occurredAt, Integer progress, String stageName, String creatorSub,
      Map<String, Object> metadata, OffsetDateTime createdAt, OffsetDateTime updatedAt,
      Long actorPlayerId, String actorName) {
    public TimelineEventView {
      metadata = immutableMetadata(metadata);
    }
  }
}
