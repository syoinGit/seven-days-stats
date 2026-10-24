package com.yuki.rubinco.luminite.web;

import com.yuki.rubinco.account.security.AdminAccess;
import com.yuki.rubinco.luminite.ingest.TerrariaImportProperties;
import com.yuki.rubinco.luminite.service.LuminiteQueryService;
import com.yuki.rubinco.luminite.service.LuminiteQueryService.Context;
import com.yuki.rubinco.portal.service.GamePortalService;
import com.yuki.rubinco.portal.service.GameTimelineService;
import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.worldprogress.service.WorldProgressQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
public class LuminiteController {
  private static final String GAME_ID = LuminiteQueryService.GAME_ID;
  private static final DisplayTimeFormatter TIME_FORMATTER = new DisplayTimeFormatter();
  private static final int PAGE_SIZE = 60;

  private final GamePortalService portalService;
  private final GameTimelineService timelineService;
  private final WorldProgressQueryService progress;
  private final LuminiteQueryService luminite;
  private final TerrariaImportProperties importProperties;
  private final AdminAccess adminAccess;

  @GetMapping("/luminite")
  public String index(Model model, Authentication authentication,
      @RequestParam(defaultValue = "false") boolean spoilers,
      @RequestParam(defaultValue = "0") int page) {
    boolean admin = adminAccess.isAdmin(authentication);
    var game = portalService.card(GAME_ID);
    int safePage = Math.max(0, Math.min(page, 1000000));
    var timeline = timelineService.sharedTimeline(List.of(GAME_ID), safePage)
        .map(event -> new TimelineItem(event.eventType(), event.title(), event.body(),
            event.occurredAt(), TIME_FORMATTER.format(event.occurredAt()), event.actorPlayerId(), event.creatorSub()));
    var overview = progress.overview(GAME_ID, admin, spoilers, importProperties.staleAfter());
    model.addAttribute("game", game);
    model.addAttribute("section", "overview");
    model.addAttribute("serverStatus", GamePortalService.ServerStatus.from(game.status()));
    model.addAttribute("overview", overview);
    model.addAttribute("spoilers", spoilers);
    model.addAttribute("timeline", timeline.getContent());
    model.addAttribute("timelinePage", timeline);
    Optional<Context> context = overview.visible() ? luminite.context(admin) : Optional.empty();
    model.addAttribute("ctx", context.orElse(null));
    context.filter(Context::hasData).ifPresent(ctx -> {
      long bosses = overview.items().stream().filter(item -> "BOSS".equals(item.category())).count();
      long bossesDone = overview.items().stream()
          .filter(item -> "BOSS".equals(item.category()) && "ACHIEVED".equals(item.state())).count();
      model.addAttribute("world", luminite.world(ctx.summary()));
      model.addAttribute("events", luminite.events(ctx.summary()));
      model.addAttribute("highlights", luminite.highlights(ctx, bossesDone, bosses));
      model.addAttribute("tiles", ctx.summary().tiles());
      model.addAttribute("activity", luminite.activity(ctx, 12));
    });
    return "luminite";
  }

  @GetMapping("/luminite/items")
  public String items(Model model, Authentication authentication,
      @RequestParam(required = false) String q, @RequestParam(defaultValue = "count") String sort,
      @RequestParam(defaultValue = "0") int page) {
    return withData(model, authentication, "items", ctx -> {
      String query = trim(q);
      model.addAttribute("q", query);
      model.addAttribute("sort", sort);
      model.addAttribute("items", luminite.items(ctx, query, sort, clamp(page), PAGE_SIZE));
      return "luminite-items";
    });
  }

  @GetMapping("/luminite/items/{itemId}")
  public String item(Model model, Authentication authentication, @PathVariable int itemId) {
    return withData(model, authentication, "items", ctx -> {
      // Only items that have been stored in this world get a page.
      var detail = luminite.item(ctx, itemId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      model.addAttribute("detail", detail);
      return "luminite-item";
    });
  }

  @GetMapping("/luminite/chests")
  public String chests(Model model, Authentication authentication,
      @RequestParam(required = false) String q, @RequestParam(required = false) String kind,
      @RequestParam(defaultValue = "0") int page) {
    return withData(model, authentication, "chests", ctx -> {
      String query = trim(q);
      String safeKind = kind != null && luminite.containerKinds().contains(kind) ? kind : null;
      model.addAttribute("q", query);
      model.addAttribute("kind", safeKind);
      model.addAttribute("kinds", kinds(ctx));
      model.addAttribute("containers", luminite.containers(ctx, query, safeKind, clamp(page), 30));
      return "luminite-chests";
    });
  }

  @GetMapping("/luminite/chests/{containerId}")
  public String chest(Model model, Authentication authentication, @PathVariable long containerId) {
    return withData(model, authentication, "chests", ctx -> {
      model.addAttribute("container", luminite.container(ctx, containerId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
      return "luminite-chest";
    });
  }

  @GetMapping("/luminite/bestiary")
  public String bestiary(Model model, Authentication authentication,
      @RequestParam(required = false) String q, @RequestParam(defaultValue = "kills") String sort) {
    return withData(model, authentication, "bestiary", ctx -> {
      String query = trim(q);
      model.addAttribute("q", query);
      model.addAttribute("sort", sort);
      model.addAttribute("rows", luminite.bestiary(ctx, query, sort));
      model.addAttribute("totals", ctx.summary().totals());
      return "luminite-bestiary";
    });
  }

  @GetMapping("/luminite/world")
  public String world(Model model, Authentication authentication) {
    return withData(model, authentication, "world", ctx -> {
      var summary = ctx.summary();
      model.addAttribute("world", luminite.world(summary));
      model.addAttribute("tiles", summary.tiles());
      model.addAttribute("totals", summary.totals());
      model.addAttribute("resourceGroups", luminite.resourceGroups(summary));
      model.addAttribute("objects", luminite.objects(summary));
      model.addAttribute("npcs", luminite.townNpcs(summary));
      model.addAttribute("pylons", luminite.pylons(summary));
      model.addAttribute("kinds", kinds(ctx));
      return "luminite-world";
    });
  }

  @GetMapping("/luminite/deaths")
  public String deaths(Model model, Authentication authentication) {
    return withData(model, authentication, "deaths", ctx -> {
      model.addAttribute("tombstones", luminite.signs(ctx, true));
      model.addAttribute("signs", luminite.signs(ctx, false));
      return "luminite-deaths";
    });
  }

  @GetMapping("/luminite/history")
  public String history(Model model, Authentication authentication,
      @RequestParam(defaultValue = "false") boolean spoilers,
      @RequestParam(defaultValue = "0") int page) {
    boolean admin = adminAccess.isAdmin(authentication);
    model.addAttribute("game", portalService.card(GAME_ID));
    model.addAttribute("section", "history");
    model.addAttribute("events", progress.history(GAME_ID, admin, spoilers, clamp(page), 30));
    model.addAttribute("spoilers", spoilers);
    var context = luminite.context(admin).filter(Context::hasData);
    model.addAttribute("ctx", context.orElse(null));
    model.addAttribute("activity", context.map(ctx -> luminite.activity(ctx, 60)).orElse(List.of()));
    return "luminite-history";
  }

  private String withData(Model model, Authentication authentication, String section,
      Function<Context, String> view) {
    model.addAttribute("game", portalService.card(GAME_ID));
    model.addAttribute("section", section);
    var context = luminite.context(adminAccess.isAdmin(authentication)).filter(Context::hasData);
    if (context.isEmpty()) return "luminite-pending";
    model.addAttribute("ctx", context.get());
    model.addAttribute("namesLoaded", luminite.namesLoaded());
    return view.apply(context.get());
  }

  private List<KindOption> kinds(Context ctx) {
    var counts = ctx.summary().totals().containersByKind();
    return luminite.containerKinds().stream().filter(counts::containsKey)
        .map(value -> new KindOption(value, luminite.kindLabel(value), counts.get(value)))
        .toList();
  }

  private static String trim(String value) {
    if (value == null || value.isBlank()) return null;
    String stripped = value.strip();
    return stripped.length() > 60 ? stripped.substring(0, 60) : stripped;
  }

  private static int clamp(int page) {
    return Math.max(0, Math.min(page, 100000));
  }

  public record KindOption(String value, String label, int count) { }

  public record TimelineItem(
      String eventType,
      String title,
      String body,
      OffsetDateTime occurredAt,
      String occurredAtLabel, Long actorPlayerId, String creatorSub) { }
}
