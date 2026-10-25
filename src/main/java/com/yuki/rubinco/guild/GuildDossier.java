package com.yuki.rubinco.guild;

import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.octarine.names.CoreKeeperNames;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What the world looked like around a memory, for the AI to read before it decides who was born:
 * the whole day in that game, how far the world had come by then (achievement rates), and one player
 * chest. Only counts and item, creature and milestone names; never players, chest labels or positions.
 * Chests are stored only as of the latest import, so that part says so.
 */
@Service
@RequiredArgsConstructor
public class GuildDossier {
  static final int MAX_LENGTH = 1500;
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");
  private static final Map<String, String> PROGRESS = Map.of(
      "BOSS", "ボス討伐", "EVENT", "イベント", "NPC", "住人NPC", "WORLD", "世界の変化");

  private final JdbcTemplate jdbc;
  private final TerrariaNames terrariaNames;
  private final CoreKeeperNames coreKeeperNames;

  public String build(GuildSourceService.Source source) {
    OffsetDateTime to = source.from().plusHours(GuildSourceService.WINDOW_HOURS);
    OffsetDateTime dayStart = source.from().atZoneSameInstant(GuildService.TOKYO).toLocalDate()
        .atStartOfDay(GuildService.TOKYO).toOffsetDateTime();
    OffsetDateTime dayEnd = dayStart.plusDays(1);
    List<String> lines = switch (source.game()) {
      case "7dtd" -> sevenDays(dayStart, dayEnd, to);
      case "terraria" -> terraria(dayStart, dayEnd, to);
      case "corekeeper" -> coreKeeper(dayStart, dayEnd, to);
      default -> List.of();
    };
    String text = String.join("\n", lines);
    return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
  }

  private List<String> sevenDays(OffsetDateTime dayStart, OffsetDateTime dayEnd, OffsetDateTime to) {
    List<String> lines = new ArrayList<>(List.of("［その日のあらまし］"));
    List<String> kills = jdbc.query("""
        SELECT k.target_entity_type, COUNT(*) FROM T_ENTITY_KILL_TRANSACTION k WHERE k.occurred_at >= ? AND k.occurred_at < ?
          AND LOWER(k.player_name) NOT LIKE 'zombie%' AND LOWER(k.player_name) NOT LIKE 'animal%'
        GROUP BY k.target_entity_type ORDER BY COUNT(*) DESC, k.target_entity_type LIMIT 4
        """, (rs, row) -> translate(rs.getString(1)) + " " + rs.getInt(2), dayStart, dayEnd);
    if (!kills.isEmpty()) lines.add("倒したもの: " + String.join("、", kills));
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("ゾンビの群れ", count("SELECT COUNT(*) FROM T_WORLD_EVENT_TRANSACTION WHERE event_type IN ('WANDERING_HORDE', 'SCOUT_HORDE') "
        + "AND occurred_at >= ? AND occurred_at < ?", dayStart, dayEnd));
    counts.put("建物の奥へ踏み込んだ", count("SELECT COUNT(*) FROM T_SLEEPER_TRANSACTION WHERE transaction_type <> 'SLEEPER_RESTORE' "
        + "AND occurred_at >= ? AND occurred_at < ?", dayStart, dayEnd));
    counts.put("補給物資", count("SELECT COUNT(*) FROM T_WORLD_EVENT_TRANSACTION WHERE event_type = 'AIR_DROP' "
        + "AND occurred_at >= ? AND occurred_at < ?", dayStart, dayEnd));
    counts.put("倒れた", count("SELECT COUNT(*) FROM T_WORLD_EVENT_TRANSACTION WHERE event_type = 'PLAYER_DEATH' "
        + "AND occurred_at >= ? AND occurred_at < ?", dayStart, dayEnd));
    String happened = counts.entrySet().stream().filter(entry -> entry.getValue() > 0)
        .map(entry -> entry.getKey() + " " + entry.getValue() + "回").collect(Collectors.joining("、"));
    if (!happened.isEmpty()) lines.add("出来事: " + happened);
    var xp = jdbc.queryForMap("SELECT COALESCE(SUM(xp_from_kill), 0) AS kill_xp, COALESCE(SUM(xp_from_harvesting), 0) AS harvest_xp, "
        + "COALESCE(SUM(xp_from_loot), 0) AS loot_xp FROM T_LEVEL_XP_SUMMARY_TRANSACTION WHERE occurred_at >= ? AND occurred_at < ?", dayStart, dayEnd);
    long killXp = ((Number) xp.get("kill_xp")).longValue();
    long harvestXp = ((Number) xp.get("harvest_xp")).longValue();
    long lootXp = ((Number) xp.get("loot_xp")).longValue();
    long totalXp = killXp + harvestXp + lootXp;
    if (totalXp > 0) {
      lines.add("経験の内訳: 戦い " + percent(killXp, totalXp) + "、採集 " + percent(harvestXp, totalXp) + "、物資探し " + percent(lootXp, totalXp));
    }
    Long distance = jdbc.queryForObject("SELECT SUM(movement_distance) FROM T_VEHICLE_POSITION_TRANSACTION WHERE event_type = 'VEHICLE_MOVE' "
        + "AND occurred_at >= ? AND occurred_at < ?", Long.class, dayStart, dayEnd);
    if (distance != null && distance >= 100) lines.add("乗り物で走った距離: およそ" + Math.round(distance / 100.0) / 10.0 + "km");
    lines.add("［その頃の世界］");
    Integer gameDay = jdbc.queryForObject("SELECT MAX(game_day) FROM T_WORLD_TIME_OBSERVATION WHERE observed_at <= ?", Integer.class, to);
    if (gameDay != null) lines.add("ゲーム内の日数: " + gameDay + "日目");
    lines.add("これまでのブラッドムーン: " + count("SELECT COUNT(*) FROM T_WORLD_EVENT_TRANSACTION WHERE event_type = 'BLOOD_MOON' AND occurred_at <= ?", to) + "回");
    return lines;
  }

  private List<String> terraria(OffsetDateTime dayStart, OffsetDateTime dayEnd, OffsetDateTime to) {
    List<String> lines = new ArrayList<>(List.of("［その日のあらまし］"));
    gains(lines, "SELECT item_id, new_total - previous_total FROM T_TERRARIA_ITEM_CHANGE WHERE observed_at >= ? AND observed_at < ? "
        + "AND new_total > previous_total", dayStart, dayEnd, id -> terrariaNames.itemCategory(id).label());
    List<String> kills = jdbc.query("SELECT npc_key, SUM(new_kills - previous_kills) FROM T_TERRARIA_KILL_CHANGE "
        + "WHERE observed_at >= ? AND observed_at < ? GROUP BY npc_key ORDER BY SUM(new_kills - previous_kills) DESC, npc_key LIMIT 4",
        (rs, row) -> terrariaNames.npc(rs.getString(1)).label() + " " + rs.getLong(2), dayStart, dayEnd);
    if (!kills.isEmpty()) lines.add("倒したもの: " + String.join("、", kills));
    Long world = world("terraria");
    if (world == null) return lines;
    progress(lines, "terraria", world, to);
    List<Long> chests = jdbc.queryForList("SELECT terraria_container_id FROM T_TERRARIA_CONTAINER WHERE game_world_id = ? "
        + "AND container_kind = 'CHEST' AND managed = TRUE AND filled_slots > 0", Long.class, world);
    if (!chests.isEmpty()) {
      long chest = chests.get(ThreadLocalRandom.current().nextInt(chests.size()));
      List<String> items = jdbc.query("SELECT item_id, SUM(stack) FROM T_TERRARIA_CONTAINER_ITEM WHERE terraria_container_id = ? "
          + "GROUP BY item_id ORDER BY SUM(stack) DESC, item_id LIMIT 8", (rs, row) -> {
            int id = rs.getInt(1);
            String name = terrariaNames.item(id).label();
            return unknown(name) ? null : name + "（" + terrariaNames.itemCategory(id).label() + "）×" + rs.getLong(2);
          }, chest);
      chest(lines, chests.size(), items);
    }
    return lines;
  }

  private List<String> coreKeeper(OffsetDateTime dayStart, OffsetDateTime dayEnd, OffsetDateTime to) {
    List<String> lines = new ArrayList<>(List.of("［その日のあらまし］"));
    int sessions = count("SELECT COUNT(*) FROM T_CK_SESSION WHERE connected_at >= ? AND connected_at < ?", dayStart, dayEnd);
    if (sessions > 0) lines.add("洞窟へ降りた回数: " + sessions + "回");
    gains(lines, "SELECT object_id, new_amount - previous_amount FROM T_CK_ITEM_CHANGE WHERE observed_at >= ? AND observed_at < ? "
        + "AND new_amount > previous_amount", dayStart, dayEnd, id -> coreKeeperNames.object(id).category().label());
    Long world = world("corekeeper");
    if (world == null) return lines;
    progress(lines, "corekeeper", world, to);
    // Only containers players labelled are stored (V46); the label itself is never read here.
    List<Long> chests = jdbc.queryForList("SELECT ck_container_id FROM T_CK_CONTAINER WHERE game_world_id = ? "
        + "AND holder = 'STORAGE' AND filled_slots > 0", Long.class, world);
    if (!chests.isEmpty()) {
      long chest = chests.get(ThreadLocalRandom.current().nextInt(chests.size()));
      // Equipment stores durability in amount, so this counts slots rather than adding amounts up.
      List<String> items = jdbc.query("SELECT object_id, COUNT(*) FROM T_CK_CONTAINER_ITEM WHERE ck_container_id = ? "
          + "GROUP BY object_id ORDER BY COUNT(*) DESC, object_id LIMIT 8", (rs, row) -> {
            var name = coreKeeperNames.object(rs.getInt(1));
            return unknown(name.label()) ? null
                : name.label() + "（" + name.category().label() + "）" + (rs.getInt(2) > 1 ? "×" + rs.getInt(2) + "枠" : "");
          }, chest);
      chest(lines, chests.size(), items);
    }
    return lines;
  }

  /** The day's increases, summed per item category, largest first. */
  private void gains(List<String> lines, String sql, OffsetDateTime from, OffsetDateTime to, IntFunction<String> category) {
    Map<String, Long> sums = new LinkedHashMap<>();
    jdbc.query(sql, rs -> {
      sums.merge(category.apply(rs.getInt(1)), rs.getLong(2), Long::sum);
    }, from, to);
    String text = sums.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
        .map(entry -> entry.getKey() + " +" + entry.getValue()).collect(Collectors.joining("、"));
    if (!text.isEmpty()) lines.add("増えた持ち物の種類: " + text);
  }

  /** Achievement rates from the last snapshot taken by the end of the memory; spoilers count but stay unnamed. */
  private void progress(List<String> lines, String game, long world, OffsetDateTime to) {
    var snapshot = jdbc.query("SELECT world_snapshot_id, captured_at FROM T_WORLD_SNAPSHOT WHERE game_world_id = ? AND captured_at <= ? "
        + "ORDER BY captured_at DESC LIMIT 1", (rs, row) -> Map.entry(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)), world, to)
        .stream().findFirst();
    if (snapshot.isEmpty()) {
      lines.add("［その頃の世界］");
      lines.add("この時点の進み具合の記録はありません。");
      return;
    }
    long id = snapshot.get().getKey();
    lines.add("［その頃の世界（" + DAY.format(snapshot.get().getValue().atZoneSameInstant(GuildService.TOKYO)) + "の取り込みより）］");
    List<String> rates = jdbc.query("""
        SELECT d.category, SUM(CASE WHEN s.state = 'ACHIEVED' THEN 1 ELSE 0 END), COUNT(*) FROM M_PROGRESS_DEFINITION d
        LEFT JOIN T_PROGRESS_STATE s ON s.progress_definition_id = d.progress_definition_id AND s.world_snapshot_id = ?
        WHERE d.game_id = ? AND d.support_status = 'SUPPORTED' GROUP BY d.category ORDER BY d.category
        """, (rs, row) -> PROGRESS.getOrDefault(rs.getString(1), rs.getString(1)) + " " + rs.getInt(2) + "/" + rs.getInt(3), id, game);
    if (!rates.isEmpty()) lines.add("達成率: " + String.join("、", rates));
    String named = """
        SELECT d.display_name FROM M_PROGRESS_DEFINITION d JOIN T_PROGRESS_STATE s ON s.progress_definition_id = d.progress_definition_id
        WHERE s.world_snapshot_id = ? AND d.game_id = ? AND d.support_status = 'SUPPORTED' AND d.spoiler = FALSE AND s.state = ?
        """;
    List<String> done = jdbc.queryForList(named + " ORDER BY d.sort_order DESC LIMIT 5", String.class, id, game, "ACHIEVED");
    if (!done.isEmpty()) lines.add("達成していたこと（新しい段階から）: " + String.join("、", done));
    List<String> next = jdbc.queryForList(named + " AND d.category = 'BOSS' ORDER BY d.sort_order LIMIT 2", String.class, id, game, "NOT_ACHIEVED");
    if (!next.isEmpty()) lines.add("まだ果たしていないこと: " + String.join("、", next));
  }

  /** Items whose names table entry is missing ("#7") tell the AI nothing, so they are left out. */
  private static void chest(List<String> lines, int chests, List<String> items) {
    items = items.stream().filter(java.util.Objects::nonNull).toList();
    if (items.isEmpty()) return;
    lines.add("［倉庫のひとつ（最新の取り込み時点。プレイヤーの宝箱 " + chests + "個から1つ）］");
    lines.add(String.join("、", items));
  }

  private static boolean unknown(String name) {
    return name == null || name.contains("#");
  }

  private Long world(String game) {
    return jdbc.queryForList("SELECT game_world_id FROM M_GAME_WORLD WHERE game_id = ? AND active = TRUE ORDER BY game_world_id DESC LIMIT 1",
        Long.class, game).stream().findFirst().orElse(null);
  }

  private String translate(String key) {
    return jdbc.queryForList("SELECT display_text FROM M_JAPANESE_TRANSLATION WHERE localization_key = ? LIMIT 1", String.class, key)
        .stream().findFirst().orElse(key);
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private static String percent(long part, long total) {
    return Math.round(part * 100.0 / total) + "%";
  }
}
