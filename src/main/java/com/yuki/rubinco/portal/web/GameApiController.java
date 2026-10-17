package com.yuki.rubinco.portal.web;

import com.yuki.rubinco.portal.service.GamePortalService;
import com.yuki.rubinco.portal.service.GameServerStartGateway;
import com.yuki.rubinco.portal.service.GameStartService;
import com.yuki.rubinco.portal.service.GameTimelineService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameApiController {
  private final GamePortalService portalService;
  private final GameTimelineService timelineService;
  private final GameStartService gameStartService;

  @GetMapping
  public List<GamePortalService.GameCard> games() {
    return portalService.cards();
  }

  @GetMapping("/{gameId}")
  public GamePortalService.GameCard game(@PathVariable String gameId) {
    requireSupportedGame(gameId);
    return portalService.card(gameId);
  }

  @GetMapping("/{gameId}/timeline")
  public List<GameTimelineService.TimelineEventView> timeline(
      @PathVariable String gameId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    requireSupportedGame(gameId);
    return timelineService.timeline(gameId, page, size);
  }

  @GetMapping("/{gameId}/timeline/latest")
  public GameTimelineService.TimelineEventView latest(@PathVariable String gameId) {
    requireSupportedGame(gameId);
    return timelineService.latest(gameId);
  }

  @GetMapping("/{gameId}/progress")
  public ProgressResponse progress(@PathVariable String gameId) {
    requireSupportedGame(gameId);
    GamePortalService.GameCard game = portalService.card(gameId);
    return new ProgressResponse(game.id(), game.progress(), game.stage());
  }

  @PostMapping("/{gameId}/start")
  public GameServerStartGateway.StartResult start(
      @PathVariable String gameId, Authentication authentication) {
    requireSupportedGame(gameId);
    return gameStartService.requestStart(gameId, authentication.getName());
  }

  @PostMapping("/{gameId}/events")
  @ResponseStatus(HttpStatus.CREATED)
  public GameTimelineService.TimelineEventView create(
      @PathVariable String gameId,
      @Valid @RequestBody EventRequest request,
      Authentication authentication) {
    requireSupportedGame(gameId);
    try {
      return timelineService.create(gameId, request.toInput(), authentication);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
  }

  @PutMapping("/{gameId}/events/{eventId}")
  public GameTimelineService.TimelineEventView update(
      @PathVariable String gameId,
      @PathVariable String eventId,
      @Valid @RequestBody EventRequest request,
      Authentication authentication) {
    requireSupportedGame(gameId);
    try {
      return timelineService.update(gameId, eventId, request.toInput(), authentication);
    } catch (NoSuchElementException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
  }

  private void requireSupportedGame(String gameId) {
    if (!portalService.supports(gameId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
    }
  }

  public record ProgressResponse(String gameId, int progress, String stageName) { }

  public record EventRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String eventType,
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 2000) String body,
      OffsetDateTime occurredAt,
      @Min(0) @Max(100) Integer progress,
      @Size(max = 160) String stageName,
      Map<String, Object> metadata) {
    public EventRequest {
      metadata = metadata == null || metadata.isEmpty()
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    GameTimelineService.EventInput toInput() {
      return new GameTimelineService.EventInput(
          eventType, title, body, occurredAt, progress, stageName, metadata);
    }
  }
}
