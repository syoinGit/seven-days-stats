package com.yuki.rubinco.guild;

import com.yuki.rubinco.luminite.names.TerrariaItemCategory;
import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.octarine.names.CoreKeeperNames;
import com.yuki.rubinco.octarine.names.ItemCategory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A resident is born from one "memory": up to ten records from a three-hour window of a past day in
 * one game, mostly about one category (fishing, a blood moon, a trip...). The draw goes game, then
 * category, then window, so a game that logs many kills cannot crowd out the rest. Records never
 * carry player names or ids, only what happened, so the memory can be shown publicly.
 */
@Service
@RequiredArgsConstructor
public class GuildSourceService {
  static final int WINDOW_HOURS = 3;
  static final int MAX_EVENTS = 10;
  /** Of the ten, at most this many are the category's own records; the rest give context. */
  private static final int FOCUS_EVENTS = 7;
  private static final int MAX_ROWS = 5000;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");
  static final Map<String, String> GAMES = Map.of(
      "7dtd", "7 Days to Die", "terraria", "Terraria", "corekeeper", "Core Keeper");
  /** Every category a memory can be about. A game offers only those its records actually show. */
  static final Map<String, String> CATEGORIES = categories();
  private static final Pattern FISH = Pattern.compile(
      "(?i).*(bass|trout|salmon|tuna|snapper|shrimp|crate|fish|koi|cod|carp|minnow|tetra|piranha|prismite).*");
  private static final Pattern SEED = Pattern.compile("(?i).*seed.*");

  private final JdbcTemplate jdbc;
  private final GuildProperties properties;
  private final TerrariaNames terrariaNames;
  private final CoreKeeperNames coreKeeperNames;

  /** label: "Terraria・10月3日 21時〜24時の記録（釣り）"; facts: one "21:14 バスが3個増えた" per line; from: the window's start. */
  public record Source(String key, String game, String category, String label, String facts, OffsetDateTime from) { }

  /** Either a memory, or why none was chosen. */
  public record Choice(Optional<Source> source, String skipped) { }

  /** Unused windows per game and category, for the maintenance page. */
  public record Inventory(String game, String category, int unused) {
    public String gameName() {
      return GAMES.getOrDefault(game, game);
    }

    public String categoryName() {
      return CATEGORIES.getOrDefault(category, category);
    }
  }

  private record Event(OffsetDateTime at, String text, String category) { }

  private record Slot(String game, LocalDate date, int window, String category) {
    String key() {
      return "memory:" + game + ":" + date + ":" + window * WINDOW_HOURS;
    }
  }

  private static Map<String, String> categories() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("bloodmoon", "ブラッドムーン");
    map.put("combat", "戦い");
    map.put("survival", "倒れた夜");
    map.put("exploration", "探索");
    map.put("gathering", "採集");
    map.put("travel", "遠出");
    map.put("fishing", "釣り");
    map.put("building", "建築");
    map.put("mining", "採掘");
    map.put("boss", "大物との戦い");
    map.put("hunting", "狩り");
    map.put("cooking", "料理");
    map.put("farming", "畑仕事");
    return Collections.unmodifiableMap(map);
  }

  // Each game's records as (happened_at, kind, detail, amount). Two range parameters per branch.
  private static final Map<String, String> EVENTS = Map.of(
      "7dtd", """
          SELECT k.occurred_at AS happened_at, 'kill' AS kind,
            COALESCE((SELECT tr.display_text FROM M_JAPANESE_TRANSLATION tr
              WHERE tr.localization_key = k.target_entity_type LIMIT 1), k.target_entity_type) AS detail, 0 AS amount
          FROM T_ENTITY_KILL_TRANSACTION k WHERE k.occurred_at >= ? AND k.occurred_at < ?
            AND LOWER(k.player_name) NOT LIKE 'zombie%' AND LOWER(k.player_name) NOT LIKE 'animal%'
          UNION ALL SELECT occurred_at, 'join', CAST(NULL AS VARCHAR), 0 FROM T_PLAYER_JOIN_TRANSACTION
            WHERE occurred_at >= ? AND occurred_at < ?
          UNION ALL SELECT occurred_at, 'leave', CAST(NULL AS VARCHAR), 0 FROM T_PLAYER_LEAVE_TRANSACTION
            WHERE occurred_at >= ? AND occurred_at < ?
          UNION ALL SELECT occurred_at, event_type, CAST(NULL AS VARCHAR), 0 FROM T_WORLD_EVENT_TRANSACTION
            WHERE event_type IN ('PLAYER_DEATH', 'BLOOD_MOON', 'WANDERING_HORDE', 'SCOUT_HORDE', 'SCREAMER_SPAWN', 'AIR_DROP')
              AND occurred_at >= ? AND occurred_at < ?
          UNION ALL SELECT occurred_at, 'sleeper', CAST(NULL AS VARCHAR), 0 FROM T_SLEEPER_TRANSACTION
            WHERE transaction_type <> 'SLEEPER_RESTORE' AND occurred_at >= ? AND occurred_at < ?
          UNION ALL SELECT occurred_at, 'levelup',
            CASE WHEN xp_from_harvesting >= xp_from_loot AND xp_from_harvesting >= xp_from_kill THEN 'harvest'
              WHEN xp_from_loot >= xp_from_kill THEN 'loot' ELSE 'kill' END, 0
            FROM T_LEVEL_XP_SUMMARY_TRANSACTION WHERE occurred_at >= ? AND occurred_at < ?
          UNION ALL SELECT occurred_at, 'drive', CAST(NULL AS VARCHAR), CAST(movement_distance AS BIGINT)
            FROM T_VEHICLE_POSITION_TRANSACTION WHERE event_type = 'VEHICLE_MOVE' AND movement_distance >= 50
              AND occurred_at >= ? AND occurred_at < ?
          """,
      "terraria", """
          SELECT observed_at AS happened_at, 'item' AS kind, CAST(item_id AS VARCHAR) AS detail,
            new_total - previous_total AS amount FROM T_TERRARIA_ITEM_CHANGE WHERE observed_at >= ? AND observed_at < ?
          UNION ALL SELECT observed_at, 'kill', npc_key, new_kills - previous_kills FROM T_TERRARIA_KILL_CHANGE
            WHERE observed_at >= ? AND observed_at < ?
          UNION ALL SELECT e.first_observed_at, 'progress', d.display_name, 0 FROM T_PROGRESS_EVENT e
            JOIN M_PROGRESS_DEFINITION d ON d.progress_definition_id = e.progress_definition_id
            WHERE d.game_id = 'terraria' AND e.change_kind = 'CHANGED' AND d.spoiler = FALSE
              AND e.first_observed_at >= ? AND e.first_observed_at < ?
          """,
      "corekeeper", """
          SELECT connected_at AS happened_at, 'join' AS kind, CAST(NULL AS VARCHAR) AS detail, 0 AS amount
            FROM T_CK_SESSION WHERE connected_at >= ? AND connected_at < ?
          UNION ALL SELECT disconnected_at, 'leave', CAST(NULL AS VARCHAR), 0 FROM T_CK_SESSION
            WHERE disconnected_at >= ? AND disconnected_at < ?
          UNION ALL SELECT observed_at, 'item', CAST(object_id AS VARCHAR), new_amount - previous_amount
            FROM T_CK_ITEM_CHANGE WHERE observed_at >= ? AND observed_at < ?
          UNION ALL SELECT e.first_observed_at, 'progress', d.display_name, 0 FROM T_PROGRESS_EVENT e
            JOIN M_PROGRESS_DEFINITION d ON d.progress_definition_id = e.progress_definition_id
            WHERE d.game_id = 'corekeeper' AND e.change_kind = 'CHANGED' AND d.spoiler = FALSE
              AND e.first_observed_at >= ? AND e.first_observed_at < ?
          """);

  /**
   * A random unused memory from a day before today. Recent days are preferred by recent-ratio, and a
   * category that already makes up category-cap of the last category-window residents is left out.
   */
  public Choice choose(LocalDate today) {
    List<Slot> slots = slots(today);
    if (slots.isEmpty()) return new Choice(Optional.empty(), "まだ使っていない過去の記録がありません。人物の追加を見送りました。");
    Set<String> capped = cappedCategories();
    List<Slot> allowed = slots.stream().filter(slot -> !capped.contains(slot.category())).toList();
    if (allowed.isEmpty()) {
      return new Choice(Optional.empty(), "残っている記録が、直近の住人と同じ種類（"
          + capped.stream().map(CATEGORIES::get).collect(Collectors.joining("・")) + "）だけでした。偏りを防ぐため見送りました。");
    }
    LocalDate cutoff = today.minusDays(properties.recentDays());
    boolean recent = ThreadLocalRandom.current().nextDouble() < properties.recentRatio();
    Optional<Source> source = pick(allowed, slot -> recent == !slot.date().isBefore(cutoff));
    if (source.isEmpty()) source = pick(allowed, slot -> true);
    return new Choice(source, source.isPresent() ? null : "まだ使っていない過去の記録がありません。人物の追加を見送りました。");
  }

  /** How many unused windows each game offers per category. */
  public List<Inventory> inventory(LocalDate today) {
    Map<String, Map<String, Integer>> counts = new TreeMap<>();
    for (Slot slot : slots(today)) {
      counts.computeIfAbsent(slot.game(), game -> new TreeMap<>()).merge(slot.category(), 1, Integer::sum);
    }
    List<Inventory> rows = new ArrayList<>();
    counts.forEach((game, categories) -> categories.forEach((category, unused) -> rows.add(new Inventory(game, category, unused))));
    rows.sort(Comparator.comparing(Inventory::game).thenComparing(row -> List.copyOf(CATEGORIES.keySet()).indexOf(row.category())));
    return rows;
  }

  /** Categories of the last category-window residents, newest first (null for those born before categories). */
  public List<String> recentCategories() {
    return jdbc.queryForList("SELECT s.category FROM T_GUILD_SOURCE s JOIN M_GUILD_CHARACTER c ON c.id = s.character_id "
        + "ORDER BY c.id DESC LIMIT ?", String.class, properties.categoryWindow());
  }

  private Set<String> cappedCategories() {
    Map<String, Long> counts = recentCategories().stream().filter(java.util.Objects::nonNull)
        .collect(Collectors.groupingBy(category -> category, Collectors.counting()));
    return counts.entrySet().stream().filter(entry -> entry.getValue() >= properties.categoryCap())
        .map(Map.Entry::getKey).collect(Collectors.toSet());
  }

  /** Game first, then one of its categories, then a window: each step is an even draw. */
  private Optional<Source> pick(List<Slot> slots, Predicate<Slot> wanted) {
    Map<String, Map<String, List<Slot>>> pool = new TreeMap<>();
    slots.stream().filter(wanted).forEach(slot -> pool.computeIfAbsent(slot.game(), game -> new TreeMap<>())
        .computeIfAbsent(slot.category(), category -> new ArrayList<>()).add(slot));
    List<String> games = new ArrayList<>(pool.keySet());
    if (games.isEmpty()) return Optional.empty();
    var random = ThreadLocalRandom.current();
    Map<String, List<Slot>> categories = pool.get(games.get(random.nextInt(games.size())));
    List<Slot> windows = categories.get(new ArrayList<>(categories.keySet()).get(random.nextInt(categories.size())));
    return memory(windows.get(random.nextInt(windows.size())));
  }

  /** Every unused (game, day, window, category) before today. */
  private List<Slot> slots(LocalDate today) {
    Set<String> used = new HashSet<>(jdbc.queryForList("SELECT source_key FROM T_GUILD_SOURCE", String.class));
    List<Slot> slots = new ArrayList<>();
    for (String game : EVENTS.keySet()) {
      Map<String, Set<String>> windows = new TreeMap<>();
      Map<String, Slot> first = new LinkedHashMap<>();
      jdbc.query("SELECT DISTINCT CAST(happened_at AT TIME ZONE 'Asia/Tokyo' AS DATE) AS d, "
          + "EXTRACT(HOUR FROM happened_at AT TIME ZONE 'Asia/Tokyo') AS h, kind, "
          + "CASE WHEN kind IN ('item', 'levelup') THEN detail END AS detail, "
          + "CASE WHEN amount > 0 THEN 1 WHEN amount < 0 THEN -1 ELSE 0 END AS direction FROM ("
          + EVENTS.get(game) + ") e", rs -> {
            Slot slot = new Slot(game, rs.getObject("d", LocalDate.class), rs.getInt("h") / WINDOW_HOURS, null);
            String category = category(game, rs.getString("kind"), rs.getString("detail"), rs.getInt("direction"));
            Set<String> found = windows.computeIfAbsent(slot.key(), key -> new TreeSet<>());
            first.putIfAbsent(slot.key(), slot);
            if (category != null) found.add(category);
          }, range(game, start(LocalDate.of(2000, 1, 1)), start(today)));
      windows.forEach((key, categories) -> {
        if (used.contains(key)) return;
        Slot slot = first.get(key);
        for (String category : bloodMoon(categories)) slots.add(new Slot(game, slot.date(), slot.window(), category));
      });
    }
    return slots;
  }

  /** On a blood moon night, the fighting is the blood moon. */
  private static Set<String> bloodMoon(Set<String> categories) {
    if (!categories.contains("bloodmoon")) return categories;
    Set<String> merged = new TreeSet<>(categories);
    merged.remove("combat");
    return merged;
  }

  /** Up to ten records of one window: mostly the category's own, the rest for context, in time order. */
  private Optional<Source> memory(Slot slot) {
    OffsetDateTime from = start(slot.date()).plusHours((long) slot.window() * WINDOW_HOURS);
    List<Event> events = jdbc.query("SELECT * FROM (" + EVENTS.get(slot.game()) + ") e LIMIT " + MAX_ROWS,
        (rs, row) -> event(slot.game(), rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3), rs.getLong(4)),
        range(slot.game(), from, from.plusHours(WINDOW_HOURS))).stream().filter(e -> e.text() != null).toList();
    boolean bloodMoon = events.stream().anyMatch(e -> "bloodmoon".equals(e.category()));
    List<Event> focus = new ArrayList<>();
    List<Event> context = new ArrayList<>();
    for (Event event : events) {
      String category = bloodMoon && "combat".equals(event.category()) ? "bloodmoon" : event.category();
      (slot.category().equals(category) ? focus : context).add(event);
    }
    if (focus.isEmpty()) return Optional.empty();
    Collections.shuffle(focus);
    Collections.shuffle(context);
    List<Event> picked = new ArrayList<>(focus.subList(0, Math.min(FOCUS_EVENTS, focus.size())));
    context.stream().limit(MAX_EVENTS - picked.size()).forEach(picked::add);
    // Fill from the focus again when there is little context.
    focus.stream().skip(Math.min(FOCUS_EVENTS, focus.size())).limit(MAX_EVENTS - picked.size()).forEach(picked::add);
    picked.sort(Comparator.comparing(Event::at));
    String facts = picked.stream()
        .map(e -> TIME.format(e.at().atZoneSameInstant(GuildService.TOKYO)) + " " + e.text())
        .collect(Collectors.joining("\n"));
    int hour = slot.window() * WINDOW_HOURS;
    String label = GAMES.get(slot.game()) + "・" + DAY.format(slot.date()) + " " + hour + "時〜" + (hour + WINDOW_HOURS)
        + "時の記録（" + CATEGORIES.get(slot.category()) + "）";
    return Optional.of(new Source(slot.key(), slot.game(), slot.category(), label,
        facts.length() > 1000 ? facts.substring(0, 1000) : facts, from));
  }

  /** The category a record speaks for, or null when it only gives context (arrivals, items used up). */
  private String category(String game, String kind, String detail, int direction) {
    return switch (game + ":" + kind) {
      case "7dtd:kill", "7dtd:WANDERING_HORDE", "7dtd:SCOUT_HORDE", "7dtd:SCREAMER_SPAWN" -> "combat";
      case "7dtd:BLOOD_MOON" -> "bloodmoon";
      case "7dtd:PLAYER_DEATH" -> "survival";
      case "7dtd:AIR_DROP", "7dtd:sleeper" -> "exploration";
      case "7dtd:drive" -> "travel";
      case "7dtd:levelup" -> switch (String.valueOf(detail)) {
        case "harvest" -> "gathering";
        case "loot" -> "exploration";
        default -> "combat";
      };
      case "terraria:item" -> direction > 0 ? terrariaItem(Integer.parseInt(detail)) : null;
      case "terraria:kill" -> "hunting";
      case "terraria:progress", "corekeeper:progress" -> "boss";
      case "corekeeper:join" -> "exploration";
      case "corekeeper:item" -> direction > 0 ? coreKeeperItem(Integer.parseInt(detail)) : null;
      default -> null;
    };
  }

  private String terrariaItem(int id) {
    var name = terrariaNames.item(id);
    if (FISH.matcher(name.key() + " " + name.en()).matches()) return "fishing";
    TerrariaItemCategory category = terrariaNames.itemCategory(id);
    return switch (category) {
      case ORE -> "mining";
      case BLOCK, PLACEABLE -> "building";
      default -> null;
    };
  }

  private String coreKeeperItem(int id) {
    var name = coreKeeperNames.object(id);
    if (SEED.matcher(name.key() + " " + name.en()).matches()) return "farming";
    ItemCategory category = name.category();
    return switch (category) {
      case FOOD -> "cooking";
      case ORE -> "mining";
      case PLACEABLE -> "building";
      default -> null;
    };
  }

  private Event event(String game, OffsetDateTime at, String kind, String detail, long amount) {
    String text = switch (game + ":" + kind) {
      case "7dtd:kill" -> detail + "を倒した";
      case "7dtd:join" -> "誰かがこの地にやって来た";
      case "7dtd:leave" -> "誰かが旅立った";
      case "7dtd:PLAYER_DEATH" -> "誰かが倒れた";
      case "7dtd:BLOOD_MOON" -> "ブラッドムーンが昇った";
      case "7dtd:WANDERING_HORDE", "7dtd:SCOUT_HORDE" -> "ゾンビの群れが近づいてきた";
      case "7dtd:SCREAMER_SPAWN" -> "スクリーマーの叫び声が響いた";
      case "7dtd:AIR_DROP" -> "補給物資が空から降ってきた";
      case "7dtd:sleeper" -> "誰かが建物の奥へ踏み込んだ";
      case "7dtd:levelup" -> switch (String.valueOf(detail)) {
        case "harvest" -> "採集を重ねて腕が上がった";
        case "loot" -> "物資を探し歩いて腕が上がった";
        default -> "戦いを重ねて腕が上がった";
      };
      case "7dtd:drive" -> "乗り物で" + amount + "mほど走った";
      case "terraria:item" -> change(terrariaNames.item(Integer.parseInt(detail)).label(), amount);
      case "terraria:kill" -> terrariaNames.npc(detail).label() + "を" + amount + "体倒した";
      case "corekeeper:join" -> "誰かが洞窟へ降りてきた";
      case "corekeeper:leave" -> "誰かが地上へ戻った";
      case "corekeeper:item" -> change(coreKeeperNames.object(Integer.parseInt(detail)).label(), amount);
      case "terraria:progress", "corekeeper:progress" -> detail + "を達成した";
      default -> null;
    };
    String category = text == null ? null
        : category(game, kind, kind.equals("item") || kind.equals("levelup") ? detail : null, Long.signum(amount));
    return new Event(at, text, category);
  }

  private static String change(String name, long amount) {
    if (amount == 0) return null;
    return amount > 0 ? name + "が" + amount + "個増えた" : name + "が" + -amount + "個減った";
  }

  private static Object[] range(String game, OffsetDateTime from, OffsetDateTime to) {
    int branches = EVENTS.get(game).split("\\?").length / 2;
    Object[] args = new Object[branches * 2];
    for (int i = 0; i < branches; i++) {
      args[i * 2] = from;
      args[i * 2 + 1] = to;
    }
    return args;
  }

  private static OffsetDateTime start(LocalDate date) {
    return ZonedDateTime.of(date.atStartOfDay(), GuildService.TOKYO).toOffsetDateTime();
  }
}
