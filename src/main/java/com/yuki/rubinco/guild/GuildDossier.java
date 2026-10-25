package com.yuki.rubinco.guild;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * How far the world had come when a memory happened, so the AI can tell a seasoned hand from a newcomer:
 * Terraria and Core Keeper achievement rates from the last snapshot by then, 7 Days to Die's in-game day
 * and how many places had been reached. Counts and milestone names only; achieved spoilers go by a hazy epithet.
 */
@Service
@RequiredArgsConstructor
public class GuildDossier {
  static final int MAX_LENGTH = 1500;
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");
  private static final Map<String, String> PROGRESS = Map.of(
      "BOSS", "ボス討伐", "EVENT", "イベント", "NPC", "住人NPC", "WORLD", "世界の変化");

  private final JdbcTemplate jdbc;

  public String build(GuildSourceService.Source source) {
    List<String> lines = source.game().equals("7dtd") ? sevenDays(source.from()) : progress(source.game(), source.from());
    String text = String.join("\n", lines);
    return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
  }

  private List<String> sevenDays(OffsetDateTime at) {
    List<String> lines = new ArrayList<>();
    Integer gameDay = jdbc.queryForObject("SELECT MAX(game_day) FROM T_WORLD_TIME_OBSERVATION WHERE observed_at <= ?", Integer.class, at);
    if (gameDay != null) lines.add("ゲーム内の日数: " + gameDay + "日目");
    Integer places = jdbc.queryForObject("""
        SELECT COUNT(*) FROM M_WORLD_POI poi WHERE COALESCE(poi.category, '') <> 'part' AND poi.poi_name NOT LIKE 'part_%'
          AND EXISTS (SELECT 1 FROM T_PLAYER_POSITION_TRANSACTION pos WHERE pos.occurred_at <= ? AND pos.movement_mode <> 'VEHICLE'
            AND pos.position_x BETWEEN poi.x - 80 AND poi.x + 80 AND pos.position_z BETWEEN poi.z - 80 AND poi.z + 80
            AND (pos.position_x - poi.x) * (pos.position_x - poi.x) + (pos.position_z - poi.z) * (pos.position_z - poi.z) <= 6400)
        """, Integer.class, at);
    Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM M_WORLD_POI WHERE COALESCE(category, '') <> 'part' AND poi_name NOT LIKE 'part_%'",
        Integer.class);
    if (total != null && total > 0) lines.add("それまでに足を踏み入れた場所: " + places + "/" + total + "か所");
    return lines;
  }

  /** Achievement rates from the last snapshot taken by the memory's time. */
  private List<String> progress(String game, OffsetDateTime at) {
    List<String> lines = new ArrayList<>();
    Long world = jdbc.queryForList("SELECT game_world_id FROM M_GAME_WORLD WHERE game_id = ? AND active = TRUE ORDER BY game_world_id DESC LIMIT 1",
        Long.class, game).stream().findFirst().orElse(null);
    if (world == null) return lines;
    var snapshot = jdbc.query("SELECT world_snapshot_id, captured_at FROM T_WORLD_SNAPSHOT WHERE game_world_id = ? AND captured_at <= ? "
        + "ORDER BY captured_at DESC LIMIT 1", (rs, row) -> Map.entry(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)), world, at)
        .stream().findFirst();
    if (snapshot.isEmpty()) {
      lines.add("この時点の進み具合の記録はありません。");
      return lines;
    }
    long id = snapshot.get().getKey();
    lines.add("（" + DAY.format(snapshot.get().getValue().atZoneSameInstant(GuildService.TOKYO)) + "の取り込みより）");
    List<String> rates = jdbc.query("""
        SELECT d.category, SUM(CASE WHEN s.state = 'ACHIEVED' THEN 1 ELSE 0 END), COUNT(*) FROM M_PROGRESS_DEFINITION d
        LEFT JOIN T_PROGRESS_STATE s ON s.progress_definition_id = d.progress_definition_id AND s.world_snapshot_id = ?
        WHERE d.game_id = ? AND d.support_status = 'SUPPORTED' GROUP BY d.category ORDER BY d.category
        """, (rs, row) -> PROGRESS.getOrDefault(rs.getString(1), rs.getString(1)) + " " + rs.getInt(2) + "/" + rs.getInt(3), id, game);
    if (!rates.isEmpty()) lines.add("達成率: " + String.join("、", rates));
    // Achieved spoilers appear by their hazy epithet; ones not yet achieved stay unmentioned.
    List<String> done = jdbc.query("""
        SELECT d.progress_key, d.display_name, d.spoiler FROM M_PROGRESS_DEFINITION d
        JOIN T_PROGRESS_STATE s ON s.progress_definition_id = d.progress_definition_id
        WHERE s.world_snapshot_id = ? AND d.game_id = ? AND d.support_status = 'SUPPORTED' AND s.state = 'ACHIEVED'
        ORDER BY d.sort_order DESC LIMIT 5
        """, (rs, row) -> GuildSourceService.milestoneName(game, rs.getString(1), rs.getString(2), rs.getBoolean(3)), id, game);
    if (!done.isEmpty()) lines.add("達成していたこと（新しい段階から）: " + String.join("、", done));
    List<String> next = jdbc.queryForList("""
        SELECT d.display_name FROM M_PROGRESS_DEFINITION d JOIN T_PROGRESS_STATE s ON s.progress_definition_id = d.progress_definition_id
        WHERE s.world_snapshot_id = ? AND d.game_id = ? AND d.support_status = 'SUPPORTED' AND d.spoiler = FALSE AND s.state = ?
          AND d.category = 'BOSS' ORDER BY d.sort_order LIMIT 2
        """, String.class, id, game, "NOT_ACHIEVED");
    if (!next.isEmpty()) lines.add("まだ果たしていないこと: " + String.join("、", next));
    return lines;
  }
}
