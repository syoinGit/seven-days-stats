package com.yuki.rubinco.octarine.web;

import com.yuki.rubinco.account.security.AdminAccess;
import com.yuki.rubinco.octarine.ingest.CoreKeeperImportProperties;
import com.yuki.rubinco.octarine.ingest.CoreKeeperImportService;
import com.yuki.rubinco.portal.service.GamePortalService;
import com.yuki.rubinco.portal.service.GameTimelineService;
import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.worldprogress.service.WorldProgressQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class OctarineController {
  private static final String GAME_ID = CoreKeeperImportService.GAME_ID;
  private static final DisplayTimeFormatter TIME_FORMATTER = new DisplayTimeFormatter();

  private final GamePortalService portalService;
  private final GameTimelineService timelineService;
  private final WorldProgressQueryService progress;
  private final CoreKeeperImportProperties importProperties;
  private final AdminAccess adminAccess;

  @GetMapping("/octarine")
  public String index(Model model, Authentication authentication,
      @RequestParam(defaultValue = "false") boolean spoilers,
      @RequestParam(defaultValue = "0") int page) {
    var game = portalService.card(GAME_ID);
    int safePage = Math.max(0, Math.min(page, 1000000));
    var timeline = timelineService.sharedTimeline(List.of(GAME_ID), safePage)
        .map(event -> new TimelineItem(event.eventType(), event.title(), event.body(),
            event.occurredAt(), TIME_FORMATTER.format(event.occurredAt()),
            event.actorPlayerId(), event.creatorSub()));
    model.addAttribute("game", game);
    model.addAttribute("serverStatus", GamePortalService.ServerStatus.from(game.status()));
    model.addAttribute("overview", progress.overview(GAME_ID, adminAccess.isAdmin(authentication), spoilers,
        importProperties.staleAfter()));
    model.addAttribute("spoilers", spoilers);
    model.addAttribute("timeline", timeline.getContent());
    model.addAttribute("timelinePage", timeline);
    return "octarine";
  }

  @GetMapping("/octarine/history")
  public String history(Model model, Authentication authentication,
      @RequestParam(defaultValue = "false") boolean spoilers,
      @RequestParam(defaultValue = "0") int page) {
    int safePage = Math.max(0, Math.min(page, 1000000));
    model.addAttribute("game", portalService.card(GAME_ID));
    model.addAttribute("events", progress.history(GAME_ID, adminAccess.isAdmin(authentication), spoilers,
        safePage, 30));
    model.addAttribute("spoilers", spoilers);
    return "octarine-history";
  }


  public record TimelineItem(
      String eventType,
      String title,
      String body,
      OffsetDateTime occurredAt,
      String occurredAtLabel, Long actorPlayerId, String creatorSub) { }
}
