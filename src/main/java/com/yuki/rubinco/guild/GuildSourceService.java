package com.yuki.rubinco.guild;

import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.octarine.names.CoreKeeperNames;
import com.yuki.rubinco.octarine.names.ItemCategory;
import com.yuki.rubinco.watchpoint.readmodel.PoiNameService;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A resident is born from one "memory": a small, concrete piece of play with a few lines of facts.
 * <ul>
 *   <li>7 Days to Die: a place first reached on foot (a POI within 80m), or one kind of enemy
 *       defeated on one day, with where it happened. The rest of the log is too noisy to use.</li>
 *   <li>Terraria: a day's gains in chests, a boss or event already achieved, or a chest at least half full.</li>
 *   <li>Core Keeper: one chest's gains on a day, a boss or the Great Wall achieved, or a chest at least
 *       half full.</li>
 * </ul>
 * The draw goes game, kind, group (an enemy type, a kind of place...), memory, each step even, so common
 * zombies or one busy day cannot crowd out the rest. Facts never carry player names, ids, chest labels
 * or positions, so the memory can be shown publicly.
 */
@Service
@RequiredArgsConstructor
public class GuildSourceService {
  static final int LOG_FACTS = 3;
  static final int CHEST_FACTS = 6;
  private static final int POI_RADIUS = 80;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");
  static final Map<String, String> GAMES = Map.of(
      "7dtd", "7 Days to Die", "terraria", "Terraria", "corekeeper", "Core Keeper");
  /** Kinds of memory, then the categories residents born before them kept (shown, never drawn). */
  static final Map<String, String> CATEGORIES = categories();
  private static final Map<String, String> PROGRESS = Map.of("BOSS", "ボス討伐", "EVENT", "イベント", "WORLD", "世界の変化");
  /**
   * Spoilers are never named. These hazy epithets stand in for them in facts and in what the AI reads;
   * one marked a spoiler later from the maintenance page gets the plain fallback.
   */
  static final Map<String, String> EPITHETS = Map.ofEntries(
      Map.entry("corekeeper:boss_bird", "嵐を呼ぶ空の巨鳥"), Map.entry("corekeeper:boss_octopus", "深い海に潜む巨大な触手の主"),
      Map.entry("corekeeper:boss_scarab", "砂の下を這う巨大な甲虫"), Map.entry("corekeeper:boss_hydra_nature", "森に巣くう多頭の竜"),
      Map.entry("corekeeper:boss_hydra_sea", "波間にうごめく多頭の竜"), Map.entry("corekeeper:boss_hydra_desert", "砂漠に棲む多頭の竜"),
      Map.entry("corekeeper:boss_core", "大地の芯で目を覚ました何か"), Map.entry("corekeeper:boss_wall", "大壁を守る古い番人"),
      Map.entry("corekeeper:boss_giant_cicada", "地の底で鳴く巨大な虫"), Map.entry("corekeeper:boss_robot", "動き続ける古代の機械仕掛け"),
      Map.entry("corekeeper:boss_slime", "洞窟を這う巨大な粘体"), Map.entry("corekeeper:boss_larva", "地中をむさぼる大きな幼虫"),
      Map.entry("corekeeper:boss_hive", "巣の奥に棲む母なる虫"), Map.entry("corekeeper:boss_shaman", "呪いを操る部族の長"));
  private static final Set<ItemCategory> EQUIPMENT = Set.of(ItemCategory.WEAPON, ItemCategory.ARMOR, ItemCategory.TOOL);
  // A kill by a player, not by a zombie or an animal.
  private static final String PLAYER_KILL = " LOWER(k.player_name) NOT LIKE 'zombie%' AND LOWER(k.player_name) NOT LIKE 'animal%' ";
  private static final String JST_DATE = "CAST(%s AT TIME ZONE 'Asia/Tokyo' AS DATE)";

  private final JdbcTemplate jdbc;
  private final GuildProperties properties;
  private final TerrariaNames terrariaNames;
  private final CoreKeeperNames coreKeeperNames;
  private final PoiNameService poiNames;

  /** label: "7 Days to Die・10月3日 ゾンビを12体倒した日"; facts: one "21:14 古い家でゾンビを倒した" per line; from: when it happened. */
  public record Source(String key, String game, String category, String label, String facts, OffsetDateTime from) { }

  /** Either a memory, or why none was chosen. */
  public record Choice(Optional<Source> source, String skipped) { }

  /** Unused memories per game and kind, for the maintenance page. */
  public record Inventory(String game, String category, int unused) {
    public String gameName() {
      return GAMES.getOrDefault(game, game);
    }

    public String categoryName() {
      return CATEGORIES.getOrDefault(category, category);
    }
  }

  /** One unused memory. group spreads the draw; detail holds what memory() needs to read it back. */
  private record Seed(String game, String kind, String key, String group, OffsetDateTime at, List<Object> detail) { }

  private static Map<String, String> categories() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("place", "訪れた場所");
    map.put("hunt", "倒したもの");
    map.put("gain", "増えた蓄え");
    map.put("milestone", "果たしたこと");
    map.put("chest", "宝箱の中身");
    for (String[] old : new String[][] {{"bloodmoon", "ブラッドムーン"}, {"combat", "戦い"}, {"survival", "倒れた夜"},
        {"exploration", "探索"}, {"gathering", "採集"}, {"travel", "遠出"}, {"fishing", "釣り"}, {"building", "建築"},
        {"mining", "採掘"}, {"boss", "大物との戦い"}, {"hunting", "狩り"}, {"cooking", "料理"}, {"farming", "畑仕事"}}) {
      map.put(old[0], old[1]);
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * A random unused memory. Recent ones are preferred by recent-ratio (chests and milestones count as
   * recent: they are as of the latest import), and a kind that already makes up category-cap of the last category-window
   * residents is left out.
   */
  public Choice choose(LocalDate today) {
    List<Seed> seeds = seeds(today);
    if (seeds.isEmpty()) return new Choice(Optional.empty(), "まだ使っていない記録がありません。人物の追加を見送りました。");
    Set<String> capped = cappedCategories();
    List<Seed> allowed = seeds.stream().filter(seed -> !capped.contains(seed.kind())).toList();
    if (allowed.isEmpty()) {
      return new Choice(Optional.empty(), "残っている記録が、直近の住人と同じ種類（"
          + capped.stream().map(CATEGORIES::get).collect(Collectors.joining("・")) + "）だけでした。偏りを防ぐため見送りました。");
    }
    LocalDate cutoff = today.minusDays(properties.recentDays());
    boolean recent = ThreadLocalRandom.current().nextDouble() < properties.recentRatio();
    Optional<Source> source = pick(allowed, seed -> recent == !jst(seed.at()).isBefore(cutoff));
    if (source.isEmpty()) source = pick(allowed, seed -> true);
    return new Choice(source, source.isPresent() ? null : "使える記録が見つかりませんでした。人物の追加を見送りました。");
  }

  /** How many unused memories each game offers per kind. */
  public List<Inventory> inventory(LocalDate today) {
    Map<String, Map<String, Integer>> counts = new TreeMap<>();
    for (Seed seed : seeds(today)) counts.computeIfAbsent(seed.game(), game -> new TreeMap<>()).merge(seed.kind(), 1, Integer::sum);
    List<Inventory> rows = new ArrayList<>();
    counts.forEach((game, kinds) -> kinds.forEach((kind, unused) -> rows.add(new Inventory(game, kind, unused))));
    rows.sort(Comparator.comparing(Inventory::game).thenComparing(row -> List.copyOf(CATEGORIES.keySet()).indexOf(row.category())));
    return rows;
  }

  /** Kinds of the last category-window residents, newest first (null for those born before kinds). */
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

  /** Game, kind, group, then a memory, each an even draw. A memory that reads back empty is dropped and redrawn. */
  private Optional<Source> pick(List<Seed> seeds, Predicate<Seed> wanted) {
    List<Seed> pool = new ArrayList<>(seeds.stream().filter(wanted).toList());
    for (int attempt = 0; attempt < 20 && !pool.isEmpty(); attempt++) {
      List<Seed> narrowed = pool;
      for (Function<Seed, String> step : List.<Function<Seed, String>>of(Seed::game, Seed::kind, Seed::group)) {
        Map<String, List<Seed>> by = narrowed.stream().collect(Collectors.groupingBy(step, TreeMap::new, Collectors.toList()));
        narrowed = new ArrayList<>(by.values()).get(ThreadLocalRandom.current().nextInt(by.size()));
      }
      Seed seed = narrowed.get(ThreadLocalRandom.current().nextInt(narrowed.size()));
      Optional<Source> source = memory(seed);
      if (source.isPresent()) return source;
      pool.remove(seed);
    }
    return Optional.empty();
  }

  /** Every unused memory. Logged ones must be from a day before today; chests are as of the latest import. */
  private List<Seed> seeds(LocalDate today) {
    OffsetDateTime before = start(today);
    List<Seed> seeds = new ArrayList<>();
    places(before, seeds);
    hunts(before, seeds);
    terrariaGains(before, seeds);
    coreKeeperGains(before, seeds);
    for (String game : List.of("terraria", "corekeeper")) {
      Long world = world(game);
      if (world == null) continue;
      var snapshot = latestSnapshot(world);
      if (snapshot == null) continue;
      milestones(game, game.equals("terraria") ? List.of("BOSS", "EVENT") : List.of("BOSS", "WORLD"), snapshot, seeds);
      chests(game, world, snapshot.getValue(), seeds);
    }
    Set<String> used = new HashSet<>(jdbc.queryForList("SELECT source_key FROM T_GUILD_SOURCE", String.class));
    seeds.removeIf(seed -> used.contains(seed.key()));
    return seeds;
  }

  private void places(OffsetDateTime before, List<Seed> seeds) {
    jdbc.query("""
        SELECT poi.id, poi.category, MIN(pos.occurred_at) FROM M_WORLD_POI poi JOIN T_PLAYER_POSITION_TRANSACTION pos
          ON pos.position_x BETWEEN poi.x - ? AND poi.x + ? AND pos.position_z BETWEEN poi.z - ? AND poi.z + ?
         AND (pos.position_x - poi.x) * (pos.position_x - poi.x) + (pos.position_z - poi.z) * (pos.position_z - poi.z) <= ?
         AND pos.movement_mode <> 'VEHICLE' AND pos.occurred_at < ?
        WHERE COALESCE(poi.category, '') <> 'part' AND poi.poi_name NOT LIKE 'part_%'
        GROUP BY poi.id, poi.category
        """, rs -> {
          long id = rs.getLong(1);
          seeds.add(new Seed("7dtd", "place", "7dtd:place:" + id, String.valueOf(rs.getString(2)),
              rs.getObject(3, OffsetDateTime.class), List.of(id)));
        }, POI_RADIUS, POI_RADIUS, POI_RADIUS, POI_RADIUS, POI_RADIUS * POI_RADIUS, before);
  }

  private void hunts(OffsetDateTime before, List<Seed> seeds) {
    String day = JST_DATE.formatted("k.occurred_at");
    jdbc.query("SELECT " + day + ", k.target_entity_type, MIN(k.occurred_at) FROM T_ENTITY_KILL_TRANSACTION k "
        + "WHERE k.occurred_at < ? AND" + PLAYER_KILL + "GROUP BY " + day + ", k.target_entity_type", rs -> {
          LocalDate date = rs.getObject(1, LocalDate.class);
          String type = rs.getString(2);
          seeds.add(new Seed("7dtd", "hunt", "7dtd:hunt:" + date + ":" + type, type, rs.getObject(3, OffsetDateTime.class), List.of(date, type)));
        }, before);
  }

  private void terrariaGains(OffsetDateTime before, List<Seed> seeds) {
    String day = JST_DATE.formatted("observed_at");
    jdbc.query("SELECT " + day + ", MIN(observed_at) FROM T_TERRARIA_ITEM_CHANGE WHERE new_total > previous_total AND observed_at < ? "
        + "GROUP BY " + day, rs -> {
          LocalDate date = rs.getObject(1, LocalDate.class);
          seeds.add(new Seed("terraria", "gain", "terraria:gain:" + date, date.toString(), rs.getObject(2, OffsetDateTime.class), List.of(date)));
        }, before);
  }

  private void coreKeeperGains(OffsetDateTime before, List<Seed> seeds) {
    String day = JST_DATE.formatted("observed_at");
    jdbc.query("SELECT " + day + ", pos_x, pos_z, MIN(observed_at) FROM T_CK_CHEST_CHANGE WHERE new_amount > previous_amount "
        + "AND observed_at < ? GROUP BY " + day + ", pos_x, pos_z", rs -> {
          LocalDate date = rs.getObject(1, LocalDate.class);
          int x = rs.getInt(2);
          int z = rs.getInt(3);
          seeds.add(new Seed("corekeeper", "gain", "corekeeper:gain:" + date + ":" + x + ":" + z, date.toString(),
              rs.getObject(4, OffsetDateTime.class), List.of(date, x, z)));
        }, before);
  }

  /** Achieved as of the latest snapshot. A milestone is something done, not a moment: it carries no time of its own. */
  private void milestones(String game, List<String> categories, Map.Entry<Long, OffsetDateTime> snapshot, List<Seed> seeds) {
    jdbc.query("""
        SELECT d.progress_key, d.category FROM M_PROGRESS_DEFINITION d JOIN T_PROGRESS_STATE s ON s.progress_definition_id = d.progress_definition_id
        WHERE s.world_snapshot_id = ? AND s.state = 'ACHIEVED' AND d.game_id = ? AND d.support_status = 'SUPPORTED' AND d.category IN (?, ?)
        """, rs -> {
          String key = rs.getString(1);
          seeds.add(new Seed(game, "milestone", game + ":milestone:" + key, rs.getString(2), snapshot.getValue(), List.of(key)));
        }, snapshot.getKey(), game, categories.get(0), categories.get(1));
  }

  /** Player chests at least half full. Rows are rebuilt every import, so a chest is known by its place. */
  private void chests(String game, long world, OffsetDateTime captured, List<Seed> seeds) {
    String sql = game.equals("terraria")
        ? "SELECT terraria_container_id, tile_x, tile_y FROM T_TERRARIA_CONTAINER WHERE game_world_id = ? AND container_kind = 'CHEST' "
          + "AND managed = TRUE AND slot_count > 0 AND filled_slots * 2 >= slot_count"
        // Only containers players labelled are stored (V46); the label itself is never read here.
        : "SELECT ck_container_id, pos_x, pos_z FROM T_CK_CONTAINER WHERE game_world_id = ? AND holder = 'STORAGE' "
          + "AND slot_count > 0 AND filled_slots * 2 >= slot_count";
    jdbc.query(sql, rs -> {
      String key = game + ":chest:" + rs.getInt(2) + ":" + rs.getInt(3);
      seeds.add(new Seed(game, "chest", key, key, captured, List.of(rs.getLong(1))));
    }, world);
  }

  private Optional<Source> memory(Seed seed) {
    String game = GAMES.get(seed.game());
    String day = DAY.format(seed.at().atZoneSameInstant(GuildService.TOKYO));
    List<String> facts;
    String label;
    switch (seed.game() + ":" + seed.kind()) {
      case "7dtd:place" -> {
        long id = (Long) seed.detail().get(0);
        var poi = jdbc.queryForMap("SELECT poi_name, x, z FROM M_WORLD_POI WHERE id = ?", id);
        String place = poiNames.displayName((String) poi.get("poi_name"));
        int x = ((Number) poi.get("x")).intValue();
        int z = ((Number) poi.get("z")).intValue();
        facts = new ArrayList<>(List.of(time(seed.at()) + " " + place + "に足を踏み入れた"));
        facts.addAll(jdbc.query("SELECT k.occurred_at, k.target_entity_type FROM T_ENTITY_KILL_TRANSACTION k "
            + "WHERE k.occurred_at >= ? AND k.occurred_at < ? AND" + PLAYER_KILL + "AND k.player_position_x IS NOT NULL "
            + "AND (k.player_position_x - ?) * (k.player_position_x - ?) + (k.player_position_z - ?) * (k.player_position_z - ?) <= ? "
            + "ORDER BY k.occurred_at LIMIT ?", (rs, row) -> time(rs.getObject(1, OffsetDateTime.class)) + " " + place + "で"
            + enemy(rs.getString(2)) + "を倒した", seed.at(), seed.at().plusHours(3), x, x, z, z, POI_RADIUS * POI_RADIUS, LOG_FACTS - 1));
        label = game + "・" + day + " " + place + "を訪れた日";
      }
      case "7dtd:hunt" -> {
        LocalDate date = (LocalDate) seed.detail().get(0);
        String type = (String) seed.detail().get(1);
        record Kill(OffsetDateTime at, Integer x, Integer z) { }
        List<Kill> kills = jdbc.query("SELECT k.occurred_at, k.player_position_x, k.player_position_z FROM T_ENTITY_KILL_TRANSACTION k "
            + "WHERE k.occurred_at >= ? AND k.occurred_at < ? AND k.target_entity_type = ? AND" + PLAYER_KILL + "ORDER BY k.occurred_at",
            (rs, row) -> new Kill(rs.getObject(1, OffsetDateTime.class), (Integer) rs.getObject(2), (Integer) rs.getObject(3)),
            start(date), start(date.plusDays(1)), type);
        if (kills.isEmpty()) return Optional.empty();
        String name = enemy(type);
        // The first, the last and one between: how the day went, not just its busiest minutes.
        List<Kill> picked = kills.size() <= LOG_FACTS ? kills : List.of(kills.getFirst(), kills.get(kills.size() / 2), kills.getLast());
        facts = picked.stream().map(kill -> {
          String place = kill.x() == null ? null : nearestPlace(kill.x(), kill.z());
          return time(kill.at()) + " " + (place == null ? "" : place + "で") + name + "を倒した";
        }).toList();
        label = game + "・" + day + " " + name + "を" + kills.size() + "体倒した日";
      }
      case "terraria:gain" -> {
        LocalDate date = (LocalDate) seed.detail().get(0);
        facts = gains(jdbc.query("SELECT observed_at, item_id, new_total - previous_total FROM T_TERRARIA_ITEM_CHANGE "
            + "WHERE observed_at >= ? AND observed_at < ? AND new_total > previous_total ORDER BY new_total - previous_total DESC",
            (rs, row) -> terrariaGain(rs.getObject(1, OffsetDateTime.class), rs.getInt(2), rs.getLong(3)), start(date), start(date.plusDays(1))));
        label = game + "・" + day + "に宝箱へ増えたもの";
      }
      case "corekeeper:gain" -> {
        LocalDate date = (LocalDate) seed.detail().get(0);
        facts = gains(jdbc.query("SELECT observed_at, object_id, new_amount - previous_amount, previous_slots, new_slots FROM T_CK_CHEST_CHANGE "
            + "WHERE observed_at >= ? AND observed_at < ? AND pos_x = ? AND pos_z = ? AND new_amount > previous_amount "
            + "ORDER BY new_amount - previous_amount DESC", (rs, row) -> coreKeeperGain(rs.getObject(1, OffsetDateTime.class), rs.getInt(2),
            rs.getLong(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5)), start(date), start(date.plusDays(1)),
            seed.detail().get(1), seed.detail().get(2)));
        label = game + "・" + day + "にひとつの宝箱へ増えたもの";
      }
      case "terraria:milestone", "corekeeper:milestone" -> {
        String key = (String) seed.detail().get(0);
        var definition = jdbc.queryForMap("SELECT display_name, category, spoiler FROM M_PROGRESS_DEFINITION WHERE game_id = ? AND progress_key = ?",
            seed.game(), key);
        String category = (String) definition.get("category");
        String name = milestoneName(seed.game(), key, (String) definition.get("display_name"), Boolean.TRUE.equals(definition.get("spoiler")));
        String kind = PROGRESS.getOrDefault(category, "達成");
        facts = List.of(name + switch (category) {
          case "BOSS" -> "を討ち果たした";
          case "EVENT" -> "を退けた";
          default -> "を成し遂げた";
        });
        label = game + "・果たしたこと（" + kind + "）";
      }
      case "terraria:chest" -> {
        facts = jdbc.query("SELECT item_id, SUM(stack) FROM T_TERRARIA_CONTAINER_ITEM WHERE terraria_container_id = ? "
            + "GROUP BY item_id ORDER BY SUM(stack) DESC, item_id", (rs, row) -> {
              int id = rs.getInt(1);
              String name = terrariaNames.item(id).label();
              return unknown(name) ? null : name + "×" + rs.getLong(2) + "（" + terrariaNames.itemCategory(id).label() + "）";
            }, seed.detail().get(0)).stream().filter(java.util.Objects::nonNull).limit(CHEST_FACTS).toList();
        label = game + "・宝箱のひとつ（" + day + "の取り込み時点）";
      }
      case "corekeeper:chest" -> {
        facts = jdbc.query("SELECT object_id, COUNT(*), SUM(amount) FROM T_CK_CONTAINER_ITEM WHERE ck_container_id = ? "
            + "GROUP BY object_id ORDER BY COUNT(*) DESC, object_id", (rs, row) -> {
              var name = coreKeeperNames.object(rs.getInt(1));
              // Equipment stores durability where stacks store a count: count its slots instead.
              long count = EQUIPMENT.contains(name.category()) ? rs.getLong(2) : rs.getLong(3);
              return unknown(name.label()) ? null : name.label() + "×" + count + "（" + name.category().label() + "）";
            }, seed.detail().get(0)).stream().filter(java.util.Objects::nonNull).limit(CHEST_FACTS).toList();
        label = game + "・宝箱のひとつ（" + day + "の取り込み時点）";
      }
      default -> throw new IllegalStateException("unknown memory " + seed.kind());
    }
    if (facts.isEmpty()) return Optional.empty();
    String text = String.join("\n", facts);
    return Optional.of(new Source(seed.key(), seed.game(), seed.kind(), label, text.length() > 1000 ? text.substring(0, 1000) : text, seed.at()));
  }

  private record Gain(OffsetDateTime at, String text, String category) { }

  /** The largest gains, one per item category first so a day of dirt does not hide the rest; then by time. */
  private static List<String> gains(List<Gain> rows) {
    List<Gain> known = rows.stream().filter(java.util.Objects::nonNull).toList();
    List<Gain> picked = new ArrayList<>();
    Set<String> categories = new HashSet<>();
    for (Gain gain : known) if (picked.size() < LOG_FACTS && categories.add(gain.category())) picked.add(gain);
    for (Gain gain : known) if (picked.size() < LOG_FACTS && !picked.contains(gain)) picked.add(gain);
    return picked.stream().sorted(Comparator.comparing(Gain::at)).map(gain -> time(gain.at()) + " " + gain.text()).toList();
  }

  private Gain terrariaGain(OffsetDateTime at, int id, long amount) {
    String name = terrariaNames.item(id).label();
    return unknown(name) ? null : new Gain(at, "宝箱の" + name + "が" + amount + "個増えた", terrariaNames.itemCategory(id).name());
  }

  private Gain coreKeeperGain(OffsetDateTime at, int id, long amount, Integer previousSlots, Integer newSlots) {
    var name = coreKeeperNames.object(id);
    if (unknown(name.label())) return null;
    long count = amount;
    if (EQUIPMENT.contains(name.category())) {
      if (previousSlots == null || newSlots == null || newSlots <= previousSlots) return null;
      count = newSlots - previousSlots;
    }
    return new Gain(at, "宝箱に" + name.label() + "が" + count + "個増えた", name.category().name());
  }

  /** The nearest place within 80m of a point, or null in the wilds. */
  private String nearestPlace(int x, int z) {
    return jdbc.query("SELECT poi_name FROM M_WORLD_POI WHERE x BETWEEN ? AND ? AND z BETWEEN ? AND ? "
        + "AND (x - ?) * (x - ?) + (z - ?) * (z - ?) <= ? AND COALESCE(category, '') <> 'part' AND poi_name NOT LIKE 'part_%' "
        + "ORDER BY (x - ?) * (x - ?) + (z - ?) * (z - ?) LIMIT 1", (rs, row) -> poiNames.displayName(rs.getString(1)),
        x - POI_RADIUS, x + POI_RADIUS, z - POI_RADIUS, z + POI_RADIUS, x, x, z, z, POI_RADIUS * POI_RADIUS, x, x, z, z)
        .stream().findFirst().orElse(null);
  }

  private String enemy(String type) {
    return jdbc.queryForList("SELECT display_text FROM M_JAPANESE_TRANSLATION WHERE localization_key = ? LIMIT 1", String.class, type)
        .stream().findFirst().orElse(type);
  }

  private Long world(String game) {
    return jdbc.queryForList("SELECT game_world_id FROM M_GAME_WORLD WHERE game_id = ? AND active = TRUE ORDER BY game_world_id DESC LIMIT 1",
        Long.class, game).stream().findFirst().orElse(null);
  }

  private Map.Entry<Long, OffsetDateTime> latestSnapshot(long world) {
    return jdbc.query("SELECT world_snapshot_id, captured_at FROM T_WORLD_SNAPSHOT WHERE game_world_id = ? ORDER BY captured_at DESC LIMIT 1",
        (rs, row) -> Map.entry(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)), world).stream().findFirst().orElse(null);
  }

  /** A milestone as facts and the AI may say it: its name in brackets, or for a spoiler a hazy epithet. */
  static String milestoneName(String game, String key, String displayName, boolean spoiler) {
    return spoiler ? EPITHETS.getOrDefault(game + ":" + key, "正体の知れない大物") : "「" + displayName + "」";
  }

  /** Items missing from the names tables ("#7", "アイテム #7") tell the AI nothing, so they are left out. */
  static boolean unknown(String name) {
    return name == null || name.contains("#");
  }

  private static String time(OffsetDateTime at) {
    return TIME.format(at.atZoneSameInstant(GuildService.TOKYO));
  }

  private static LocalDate jst(OffsetDateTime at) {
    return at.atZoneSameInstant(GuildService.TOKYO).toLocalDate();
  }

  private static OffsetDateTime start(LocalDate date) {
    return ZonedDateTime.of(date.atStartOfDay(), GuildService.TOKYO).toOffsetDateTime();
  }
}
