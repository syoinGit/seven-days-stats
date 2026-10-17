package com.yuki.rubinco.luminite.web;

import com.yuki.rubinco.portal.service.GamePortalService;
import com.yuki.rubinco.portal.service.GameTimelineService;
import com.yuki.rubinco.shared.DisplayTimeFormatter;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class LuminiteController {
  private static final String GAME_ID = "terraria";
  private static final DisplayTimeFormatter TIME_FORMATTER = new DisplayTimeFormatter();

  private final GamePortalService portalService;
  private final GameTimelineService timelineService;

  @GetMapping("/luminite")
  public String index(Model model, @RequestParam(defaultValue = "0") int page) {
    var game = portalService.card(GAME_ID);
    int safePage = Math.max(0, Math.min(page, 1000000));
    var timeline = timelineService.sharedTimeline(List.of(GAME_ID), safePage)
        .map(event -> new TimelineItem(event.eventType(), event.title(), event.body(),
            event.occurredAt(), TIME_FORMATTER.format(event.occurredAt()), event.actorPlayerId(), event.creatorSub()));
    model.addAttribute("game", game);
    model.addAttribute("serverStatus", GamePortalService.ServerStatus.from(game.status()));
    model.addAttribute("timeline", timeline.getContent());
    model.addAttribute("timelinePage", timeline);
    return "luminite";
  }

  public record TimelineItem(
      String eventType,
      String title,
      String body,
      OffsetDateTime occurredAt,
      String occurredAtLabel, Long actorPlayerId, String creatorSub) { }
}
