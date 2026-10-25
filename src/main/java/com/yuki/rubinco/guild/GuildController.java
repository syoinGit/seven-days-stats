package com.yuki.rubinco.guild;

import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** Every page is the tavern; the others open one of its windows (fragments/guild-windows.html) on top. */
@Controller
@RequiredArgsConstructor
public class GuildController {
  private final GuildService guild;
  private final GuildChronicle chronicle;
  private final GuildProperties properties;
  private final GuildDungeon dungeon;
  private final GuildVoice voices;

  @GetMapping("/guild")
  public String room(Model model) {
    model.addAttribute("canonical", properties.publicUrl());
    return stage(model, "tavern", null, null);
  }

  @GetMapping("/guild/visitors")
  public String visitors(Model model) {
    return stage(model, "visitors", "visitors", "今日の来店者");
  }

  @GetMapping("/guild/board")
  public String board(Model model) {
    model.addAttribute("newcomers", guild.characters(0).stream().limit(1).toList());
    model.addAttribute("recent", chronicle.recent(5));
    return stage(model, "board", "board", "依頼掲示板");
  }

  @GetMapping("/guild/characters")
  public String characters(Model model, @RequestParam(defaultValue = "0") int page) {
    int safePage = Math.max(0, Math.min(page, 10000));
    model.addAttribute("characters", guild.characters(safePage));
    model.addAttribute("page", safePage);
    return stage(model, "roster", "roster", "住人名簿");
  }

  @GetMapping("/guild/characters/{id}")
  public String character(Model model, @PathVariable long id) {
    var character = guild.character(id);
    model.addAttribute("character", character);
    model.addAttribute("voice", voices.of(character));
    model.addAttribute("relations", chronicle.relations(id));
    model.addAttribute("history", guild.history(id));
    model.addAttribute("interactions", chronicle.list(new GuildChronicle.Filter(id, null, null, null, null), 0).stream().limit(8).toList());
    model.addAttribute("expeditions", dungeon.recent(ZonedDateTime.now(GuildService.TOKYO), id, 5));
    return stage(model, "roster", "character", character.name());
  }

  /** The interaction log, filtered by resident, pair ("3-8"), kind or the pair's current label. */
  @GetMapping("/guild/log")
  public String log(Model model, @RequestParam(required = false) Long character, @RequestParam(required = false) String pair,
                    @RequestParam(required = false) String kind, @RequestParam(required = false) String label,
                    @RequestParam(defaultValue = "0") int page) {
    long[] ids = pair == null || !pair.matches("\\d{1,18}-\\d{1,18}") ? null
        : java.util.Arrays.stream(pair.split("-")).mapToLong(Long::parseLong).toArray();
    String safeKind = kind == null || kind.isBlank() || kind.length() > 20 ? null : kind;
    String safeLabel = label == null || !GuildInteractionService.LABELS.containsKey(label) ? null : label;
    var filter = new GuildChronicle.Filter(character, ids == null ? null : ids[0], ids == null ? null : ids[1], safeKind, safeLabel);
    int safePage = Math.max(0, Math.min(page, 10000));
    model.addAttribute("entries", chronicle.list(filter, safePage));
    model.addAttribute("filter", filter);
    model.addAttribute("kinds", chronicle.kinds());
    model.addAttribute("labels", GuildInteractionService.LABELS);
    model.addAttribute("filterCharacter", character == null ? null : chronicle.person(character));
    model.addAttribute("page", safePage);
    model.addAttribute("pageSize", GuildChronicle.PAGE);
    return stage(model, "log", "log", "交流の記録");
  }

  @GetMapping("/guild/log/{id}")
  public String entry(Model model, @PathVariable long id) {
    model.addAttribute("detail", chronicle.detail(id));
    return stage(model, "log", "entry", "交流の記録");
  }

  /** The newest dungeon run, as far as it has come. */
  @GetMapping("/guild/dungeon")
  public String dungeon(Model model) {
    var now = ZonedDateTime.now(GuildService.TOKYO);
    return run(model, dungeon.latest(now), now);
  }

  @GetMapping("/guild/dungeon/{id}")
  public String dungeonRun(Model model, @PathVariable long id) {
    var now = ZonedDateTime.now(GuildService.TOKYO);
    return run(model, dungeon.expedition(id, now), now);
  }

  private String run(Model model, GuildDungeon.Expedition expedition, ZonedDateTime now) {
    model.addAttribute("expedition", expedition);
    model.addAttribute("members", expedition == null ? java.util.List.of()
        : expedition.members().stream().map(member -> guild.character(member.id())).toList());
    model.addAttribute("runs", dungeon.recent(now, null, 10));
    model.addAttribute("bonds", expedition == null || !expedition.back() ? java.util.List.of() : dungeon.bonds(expedition.id()));
    return stage(model, "dungeon", "dungeon", "ダンジョン");
  }

  @GetMapping("/guild/pairs/{a}-{b}")
  public String pair(Model model, @PathVariable long a, @PathVariable long b) {
    if (a >= b) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var first = chronicle.person(a);
    var second = chronicle.person(b);
    model.addAttribute("first", first);
    model.addAttribute("second", second);
    model.addAttribute("story", chronicle.pairStory(a, b));
    model.addAttribute("labels", chronicle.pairLabels(a, b));
    model.addAttribute("threads", chronicle.pairThreads(a, b));
    return stage(model, "log", "pair", first.name() + "と" + second.name());
  }

  private String stage(Model model, String tab, String window, String title) {
    var now = ZonedDateTime.now(GuildService.TOKYO);
    var room = guild.room(now);
    model.addAttribute("room", room);
    var scene = chronicle.scene(now, room.date());
    model.addAttribute("scene", scene);
    var todayRun = dungeon.day(room.date(), now);
    model.addAttribute("todayRun", todayRun);
    model.addAttribute("runMembers", todayRun == null ? java.util.List.of()
        : todayRun.members().stream().map(member -> guild.character(member.id())).toList());
    java.util.Set<Long> returned = todayRun == null || !todayRun.back() ? java.util.Set.of()
        : todayRun.members().stream().map(GuildChronicle.Ref::id).collect(java.util.stream.Collectors.toSet());
    var tavern = GuildTavern.arrange(room.visits(), chronicle.groups(now, room.date()),
        scene == null ? java.util.Set.of() : scene.lines().keySet(), returned, room.date(), room.phase());
    model.addAttribute("tavern", tavern);
    // What each one says to no one in particular, in their own voice and fitting where they are; the party keeps its return line.
    java.util.Map<Long, String> idle = new java.util.HashMap<>();
    for (var area : tavern.areas()) {
      if (!GuildVoice.PLACES.contains(area.key())) continue;
      for (var seat : area.seats()) idle.put(seat.visit().character().id(), voices.idle(seat.visit().character(), area.key(), room.date(), room.phase()));
    }
    model.addAttribute("idleLines", idle);
    model.addAttribute("tab", tab);
    model.addAttribute("window", window);
    model.addAttribute("windowTitle", title);
    return "guild";
  }
}
