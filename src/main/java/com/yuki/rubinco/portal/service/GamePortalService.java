package com.yuki.rubinco.portal.service;

import com.yuki.rubinco.portal.config.GameCatalogProperties;
import com.yuki.rubinco.portal.entity.M_GamePortalState;
import com.yuki.rubinco.portal.repository.M_GamePortalStateRepository;
import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GamePortalService {
  private final GameCatalogProperties properties;
  private final M_GamePortalStateRepository stateRepository;
  private final T_TimelinePostRepository timelineRepository;
  private final GameServerStartGateway gateway;

  @Transactional(readOnly = true)
  public Map<String, ServerStatus> serverStatuses() {
    var ids = properties.getCatalog().stream().map(GameCatalogProperties.Game::getId).toList();
    var states = stateRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(M_GamePortalState::getGameId,
            state -> ServerStatus.from(state.getServerStatus())));
    ids.forEach(id -> states.putIfAbsent(id, ServerStatus.from(null)));
    if (gateway.liveStatus()) {
      var liveStatuses = gateway.currentStatuses(ids);
      ids.forEach(id -> {
        if (!"maintenance".equals(states.get(id).code())) {
          states.put(id, ServerStatus.from(liveStatuses.get(id)));
        }
      });
    }
    return states;
  }

  public boolean liveServerStatus() {
    return gateway.liveStatus();
  }

  public List<ServerControl> servers(Map<String, ServerStatus> statuses) {
    return properties.getCatalog().stream().map(game -> new ServerControl(
        game.getId(), game.getSiteName(), game.isManualStart(), statuses.get(game.getId())))
        .toList();
  }

  public record ServerControl(String id, String name, boolean manualStart, ServerStatus status) { }

  public boolean canStart(String gameId) {
    return supports(gameId) && configuredGame(gameId).isManualStart();
  }

  public record ServerStatus(String code, String label) {
    public static ServerStatus from(String value) {
      if (value == null) return new ServerStatus("unknown", "未確認");
      return switch (value) {
        case "online" -> new ServerStatus(value, "稼働中");
        case "offline" -> new ServerStatus(value, "停止中");
        case "starting" -> new ServerStatus(value, "起動中");
        case "maintenance" -> new ServerStatus(value, "メンテナンス中");
        case "error" -> new ServerStatus(value, "エラー");
        default -> new ServerStatus("unknown", "未確認");
      };
    }
  }

  public List<SiteCard> sites() {
    return properties.getCatalog().stream().filter(GameCatalogProperties.Game::isSiteEnabled)
        .map(game -> new SiteCard(
        game.getId(), game.getDisplayName(), game.getSiteName(), game.getSiteUrl(), game.getAccent()))
        .toList();
  }

  public record SiteCard(String id, String displayName, String siteName, String siteUrl, String accent) { }

  @Transactional(readOnly = true)
  public List<GameCard> cards() {
    List<String> gameIds = properties.getCatalog().stream()
        .map(GameCatalogProperties.Game::getId).toList();
    Map<String, M_GamePortalState> states = stateRepository.findAllById(gameIds).stream()
        .collect(Collectors.toMap(M_GamePortalState::getGameId, Function.identity()));
    return properties.getCatalog().stream()
        .map(game -> card(game, states.get(game.getId()))).toList();
  }

  @Transactional(readOnly = true)
  public GameCard card(String gameId) {
    GameCatalogProperties.Game game = configuredGame(gameId);
    return card(game, stateRepository.findById(gameId).orElse(null));
  }

  GameCatalogProperties.Game configuredGame(String gameId) {
    return properties.getCatalog().stream()
        .filter(candidate -> candidate.getId().equals(gameId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown gameId: " + gameId));
  }

  public boolean supports(String gameId) {
    return gameId != null
        && properties.getCatalog().stream().anyMatch(game -> gameId.equals(game.getId()));
  }

  private GameCard card(GameCatalogProperties.Game game, M_GamePortalState state) {
    T_TimelinePost latest = timelineRepository
        .findTopByGameIdAndVisibleTrueOrderByPublishedAtDescIdDesc(game.getId()).orElse(null);
    String status = state == null ? "error" : state.getServerStatus();
    if (gateway.liveStatus() && !"maintenance".equals(status)) {
      status = gateway.currentStatus(game.getId());
    }
    return new GameCard(
        game.getId(), game.getDisplayName(), game.getSiteName(), game.getSiteUrl(), game.getAccent(),
        status,
        state == null ? game.getProgress() : state.getProgress(),
        state == null ? game.getStage() : state.getStageName(),
        state == null ? game.getSchedule() : state.getScheduleText(),
        state == null ? game.getVersion() : state.getGameVersion(),
        state == null ? game.getConnection() : firstNonBlank(
            state.getConnectionText(), game.getConnection()),
        latest == null ? "まだ記録がありません" : latest.getMessage(),
        latest == null ? null : latest.getPublishedAt());
  }

  private String firstNonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  public record GameCard(
      String id, String displayName, String siteName, String siteUrl, String accent,
      String status, int progress, String stage, String schedule, String version,
      String connection, String latestTimeline, OffsetDateTime latestAt) { }
}
