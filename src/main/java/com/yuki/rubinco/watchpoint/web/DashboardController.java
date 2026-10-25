package com.yuki.rubinco.watchpoint.web;

import com.yuki.rubinco.account.service.CurrentWebAccountService;
import com.yuki.rubinco.portal.service.ServerActivityGate;
import com.yuki.rubinco.timeline.service.TimelinePostService;
import com.yuki.rubinco.watchpoint.readmodel.DashboardViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
public class DashboardController {

  private final DashboardViewService dashboardViewService;
  private final CurrentWebAccountService currentAccountService;
  private final TimelinePostService timelinePostService;
  private final ServerActivityGate serverGate;

  @GetMapping("/")
  public String landing(Authentication authentication) {
    return currentAccountService.current(authentication).isPresent()
        ? "redirect:/dashboard"
        : "landing";
  }

  @GetMapping("/dashboard")
  public String index(Model model, Authentication authentication) {
    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard(false);
    model.addAttribute("dashboard", dashboard);
    // The world clock and blood moon countdown describe a running world; hide them while it is stopped.
    model.addAttribute("serverStopped", serverGate.stopped("7dtd"));
    model.addAttribute("timelinePage", TimelinePage.from(timelinePostService.feed(authentication, 0)));
    return "dashboard";
  }

  /**
   * The live dashboard initially renders only one small page. Older entries are fetched as an
   * HTML fragment when the reader reaches the bottom, keeping long-running servers from putting
   * their entire history into one response or DOM tree.
   */
  @GetMapping("/dashboard/timeline")
  public String olderTimeline(
      @RequestParam(defaultValue = "0") int offset,
      Model model,
      Authentication authentication) {
    model.addAttribute("timelinePage", TimelinePage.from(timelinePostService.feed(authentication, offset)));
    return "fragments/timeline :: page";
  }

  @GetMapping("/players/{playerId}")
  public String player(
      @PathVariable Long playerId,
      Model model,
      Authentication authentication) {
    if (currentAccountService.current(authentication)
        .map(account -> "VIEWER".equals(account.getRole()))
        .orElse(false)) {
      return "redirect:/dashboard";
    }
    DashboardViewService.PlayerDetailView player = dashboardViewService.playerDetail(playerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    model.addAttribute("player", player);
    return "player-detail";
  }

  @GetMapping("/server")
  public String server(Model model) {
    model.addAttribute("server", dashboardViewService.serverDetail());
    return "server-detail";
  }

  @GetMapping("/kills")
  public String kills(Model model) {
    model.addAttribute("kills", dashboardViewService.killDetail());
    return "kill-detail";
  }

  @GetMapping("/vehicles")
  public String vehicles(Model model) {
    model.addAttribute("vehicles", dashboardViewService.vehicleDetail());
    return "vehicle-detail";
  }

  @GetMapping("/exploration")
  public String exploration(Model model) {
    model.addAttribute("exploration", dashboardViewService.explorationDetail());
    return "exploration-detail";
  }

}
