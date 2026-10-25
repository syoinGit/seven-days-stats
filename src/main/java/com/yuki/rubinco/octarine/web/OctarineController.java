package com.yuki.rubinco.octarine.web;

import com.yuki.rubinco.account.security.AdminAccess;
import com.yuki.rubinco.octarine.ingest.CoreKeeperImportProperties;
import com.yuki.rubinco.octarine.ingest.CoreKeeperImportService;
import com.yuki.rubinco.octarine.names.ItemCategory;
import com.yuki.rubinco.octarine.service.OctarineQueryService;
import com.yuki.rubinco.octarine.service.OctarineQueryService.Context;
import com.yuki.rubinco.octarine.session.CoreKeeperSessionQueryService;
import com.yuki.rubinco.portal.service.GamePortalService;
import com.yuki.rubinco.portal.service.GameTimelineService;
import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.worldprogress.service.WorldProgressQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
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
public class OctarineController {
  private static final String GAME_ID = CoreKeeperImportService.GAME_ID;
  private static final DisplayTimeFormatter TIME_FORMATTER = new DisplayTimeFormatter();
  private static final int PAGE_SIZE = 60;

  private final GamePortalService portalService;
  private final GameTimelineService timelineService;
  private final WorldProgressQueryService progress;
  private final OctarineQueryService octarine;
  private final CoreKeeperImportProperties importProperties;
  private final AdminAccess adminAccess;
  private final CoreKeeperSessionQueryService sessions;

  @GetMapping("/octarine")
  public String index(Model model, Authentication authentication,
      @RequestParam(defaultValue = "0") int page) {
    boolean admin = adminAccess.isAdmin(authentication);
    var game = portalService.card(GAME_ID);
    int safePage = Math.max(0, Math.min(page, 1000000));
    var timeline = timelineService.sharedTimeline(List.of(GAME_ID), safePage)
        .map(event -> new TimelineItem(event.eventType(), event.title(), event.body(),
            event.occurredAt(), TIME_FORMATTER.format(event.occurredAt()),
            event.actorPlayerId(), event.creatorSub()));
    var overview = progress.overview(GAME_ID, admin, importProperties.staleAfter());
    model.addAttribute("game", game);
    model.addAttribute("section", "overview");
    model.addAttribute("serverStatus", GamePortalService.ServerStatus.from(game.status()));
    model.addAttribute("overview", overview);
    model.addAttribute("timeline", timeline.getContent());
    model.addAttribute("timelinePage", timeline);
    model.addAttribute("onlineNames", !sessions.tracking() ? null : String.join("、",
        sessions.online().stream().map(CoreKeeperSessionQueryService.Online::name).toList()));
    var context = overview.visible() ? octarine.context(admin).filter(Context::hasData) : java.util.Optional.<Context>empty();
    model.addAttribute("ctx", context.orElse(null));
    context.ifPresent(ctx -> {
      model.addAttribute("highlights", octarine.highlights(ctx, overview.achievedCount()));
      model.addAttribute("activity", octarine.activity(ctx, 8));
    });
    return "octarine";
  }

  @GetMapping("/octarine/history")
  public String history(Model model, Authentication authentication,
      @RequestParam(defaultValue = "0") int page) {
    boolean admin = adminAccess.isAdmin(authentication);
    model.addAttribute("game", portalService.card(GAME_ID));
    model.addAttribute("section", "history");
    model.addAttribute("events", progress.history(GAME_ID, admin, clamp(page), 30));
    var context = octarine.context(admin).filter(Context::hasData);
    model.addAttribute("ctx", context.orElse(null));
    model.addAttribute("activity", context.map(ctx -> octarine.activity(ctx, 60)).orElse(List.of()));
    return "octarine-history";
  }

  /** Connections come from the server log, so this page works before any save is imported. */
  @GetMapping("/octarine/players")
  public String players(Model model) {
    model.addAttribute("game", portalService.card(GAME_ID));
    model.addAttribute("section", "players");
    model.addAttribute("tracking", sessions.tracking());
    model.addAttribute("online", sessions.online());
    model.addAttribute("players", sessions.players());
    model.addAttribute("logins", sessions.recent(50));
    return "octarine-players";
  }

  @GetMapping("/octarine/items")
  public String items(Model model, Authentication authentication,
      @RequestParam(required = false) String q, @RequestParam(required = false) String c,
      @RequestParam(defaultValue = "amount") String sort, @RequestParam(defaultValue = "0") int page) {
    return withData(model, authentication, "items", ctx -> {
      String query = trim(q);
      ItemCategory category = java.util.Arrays.stream(ItemCategory.values())
          .filter(value -> value.icon().equals(c)).findFirst().orElse(null);
      model.addAttribute("q", query);
      model.addAttribute("c", category == null ? null : category.icon());
      model.addAttribute("sort", sort);
      model.addAttribute("categories", ItemCategory.values());
      model.addAttribute("items", octarine.items(ctx, query, category, sort, clamp(page), PAGE_SIZE));
      return "octarine-items";
    });
  }

  @GetMapping("/octarine/items/{objectId}")
  public String item(Model model, Authentication authentication, @PathVariable int objectId) {
    return withData(model, authentication, "items", ctx -> {
      model.addAttribute("detail", octarine.item(ctx, objectId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
      return "octarine-item";
    });
  }

  @GetMapping("/octarine/chests")
  public String chests(Model model, Authentication authentication,
      @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
    return withData(model, authentication, "chests", ctx -> {
      String query = trim(q);
      model.addAttribute("q", query);
      model.addAttribute("containers", octarine.containers(ctx, query, clamp(page), 30));
      model.addAttribute("categories", ItemCategory.values());
      return "octarine-chests";
    });
  }

  @GetMapping("/octarine/chests/{containerId}")
  public String chest(Model model, Authentication authentication, @PathVariable long containerId) {
    return withData(model, authentication, "chests", ctx -> {
      model.addAttribute("container", octarine.container(ctx, containerId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
      model.addAttribute("changes", octarine.chestChanges(ctx, containerId, 100));
      model.addAttribute("categories", ItemCategory.values());
      return "octarine-chest";
    });
  }

  private String withData(Model model, Authentication authentication, String section,
      Function<Context, String> view) {
    model.addAttribute("game", portalService.card(GAME_ID));
    model.addAttribute("section", section);
    var context = octarine.context(adminAccess.isAdmin(authentication)).filter(Context::hasData);
    if (context.isEmpty()) return "octarine-pending";
    model.addAttribute("ctx", context.get());
    model.addAttribute("namesLoaded", octarine.namesLoaded());
    return view.apply(context.get());
  }

  private static String trim(String value) {
    if (value == null || value.isBlank()) return null;
    String stripped = value.strip();
    return stripped.length() > 60 ? stripped.substring(0, 60) : stripped;
  }

  private static int clamp(int page) {
    return Math.max(0, Math.min(page, 100000));
  }

  public record TimelineItem(
      String eventType,
      String title,
      String body,
      OffsetDateTime occurredAt,
      String occurredAtLabel, Long actorPlayerId, String creatorSub) { }
}
