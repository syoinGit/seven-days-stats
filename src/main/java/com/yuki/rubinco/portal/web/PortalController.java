package com.yuki.rubinco.portal.web;

import com.yuki.rubinco.account.service.CurrentWebAccountService;
import com.yuki.rubinco.account.security.GameServerAccess;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import com.yuki.rubinco.portal.service.GamePortalService;
import com.yuki.rubinco.portal.service.GameServerStartGateway;
import com.yuki.rubinco.portal.service.GameStartService;
import com.yuki.rubinco.portal.service.GameTimelineService;
import com.yuki.rubinco.portal.service.OnlinePlayerSource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
public class PortalController {
  private final GamePortalService portalService;
  private final GameTimelineService timelineService;
  private final GameStartService gameStartService;
  private final CurrentWebAccountService currentAccountService;
  private final PortalPlatformProperties platformProperties;
  private final GameServerAccess gameServerAccess;
  private final List<OnlinePlayerSource> onlineSources;

  @GetMapping("/portal")
  public String portal(Model model, Authentication authentication,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page) {
    if (platformProperties.getCognito().isEnabled()
        && !platformProperties.isPortalHost(request.getServerName())) {
      return "redirect:" + platformProperties.portalPageUrl()
          + (request.getParameterMap().containsKey("error") ? "?login=true&error"
              : request.getParameterMap().containsKey("login") ? "?login=true" : "");
    }
    model.addAttribute("loginRequested", request.getParameterMap().containsKey("login")
        || request.getParameterMap().containsKey("error"));
    var sites = portalService.sites();
    model.addAttribute("sites", sites);
    var serverStatuses = portalService.serverStatuses();
    model.addAttribute("serverStatuses", serverStatuses);
    model.addAttribute("memoryBudget", portalService.memorySnapshot(serverStatuses));
    var servers = portalService.servers(serverStatuses);
    model.addAttribute("servers", servers);
    model.addAttribute("liveServerStatus", portalService.liveServerStatus());
    model.addAttribute("canStartServers", gameServerAccess.canStart(authentication));
    model.addAttribute("siteEntries", sites.stream().collect(Collectors.toMap(
        GamePortalService.SiteCard::id, site -> platformProperties.siteEntryUrl(site.siteUrl()))));
    model.addAttribute("siteAccents", sites.stream().collect(Collectors.toMap(
        GamePortalService.SiteCard::id, GamePortalService.SiteCard::accent)));
    model.addAttribute("cognitoEnabled", platformProperties.getCognito().isEnabled());
    model.addAttribute("loginUrl", "/oauth2/authorization/portal");
    var account = currentAccountService.current(authentication);
    model.addAttribute("siteLinks", sites.stream().collect(Collectors.toMap(GamePortalService.SiteCard::id,
        site -> account.isPresent() ? platformProperties.siteEntryUrl(site.siteUrl()) : site.siteUrl())));
    model.addAttribute("onlineCounts", account.isPresent() ? onlineCounts() : Map.of());
    if (account.isPresent()) {
      int safePage = Math.max(0, Math.min(page, 1000000));
      model.addAttribute("timeline", timelineService.sharedTimeline(
          servers.stream().map(GamePortalService.ServerControl::id).toList(), safePage));
      model.addAttribute("timelinePage", safePage);
    }
    return "portal";
  }

  /** Counts only, for members; a failing source shows nothing rather than breaking the portal. */
  private Map<String, Integer> onlineCounts() {
    Map<String, Integer> counts = new HashMap<>();
    for (OnlinePlayerSource source : onlineSources) {
      try {
        counts.put(source.gameId(), source.onlineCount());
      } catch (RuntimeException ignored) {
        // Leave this game's count out.
      }
    }
    return counts;
  }

  @GetMapping(value = "/portal/actions/games/status", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public Map<String, GamePortalService.ServerStatus> statuses() {
    return portalService.serverStatuses();
  }

  @PostMapping(value = "/portal/actions/games/{gameId}/start", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public GameServerStartGateway.StartResult start(
      @PathVariable String gameId, Authentication authentication) {
    if (!portalService.supports(gameId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
    }
    return gameStartService.requestStart(gameId, authentication.getName());
  }

  @PostMapping(value = "/portal/actions/games/{gameId}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public GameServerStartGateway.StartResult stop(
      @PathVariable String gameId, Authentication authentication) {
    if (!portalService.supports(gameId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
    }
    return gameStartService.requestStop(gameId, authentication.getName());
  }
}
