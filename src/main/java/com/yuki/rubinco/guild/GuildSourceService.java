package com.yuki.rubinco.guild;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only fixed numeric facts enter the prompt; names, chat, item labels and raw JSON never do. */
@Service
@RequiredArgsConstructor
public class GuildSourceService {
  private final JdbcTemplate jdbc;
  private final GuildProperties properties;

  public record Source(String key, String game, String facts) { }

  // Closed JST player-days and validated world snapshots, including inactive worlds.
  // Snapshot identity ignores parser version: re-parsing the same save is not a new source.
  private static final String CANDIDATES = """
      WITH joins AS (
        SELECT CASE WHEN NULLIF(cross_platform_id, '') IS NOT NULL THEN CONCAT('cross:', cross_platform_id)
          WHEN NULLIF(platform_id, '') IS NOT NULL THEN CONCAT('platform:', platform_id)
          ELSE CONCAT('master:', player_id) END AS identity_key, occurred_at
        FROM T_PLAYER_JOIN_TRANSACTION
        WHERE player_id IS NOT NULL OR NULLIF(cross_platform_id, '') IS NOT NULL OR NULLIF(platform_id, '') IS NOT NULL
      ), candidates AS (
        SELECT '7dtd' AS game, identity_key AS owner,
          CONCAT('7dtd:', identity_key, ':', CAST(occurred_at AT TIME ZONE 'Asia/Tokyo' AS DATE)) AS source_key,
          CAST(occurred_at AT TIME ZONE 'Asia/Tokyo' AS DATE) AS source_date,
          CONCAT('ある一日の接続記録。接続回数=', COUNT(*), '。行動や戦果は不明。') AS facts
        FROM joins
        GROUP BY identity_key, CAST(occurred_at AT TIME ZONE 'Asia/Tokyo' AS DATE)
        UNION ALL
        SELECT 'corekeeper', CONCAT('player:', ck_player_id),
          CONCAT('ck:', ck_player_id, ':', CAST(connected_at AT TIME ZONE 'Asia/Tokyo' AS DATE)),
          CAST(connected_at AT TIME ZONE 'Asia/Tokyo' AS DATE),
          CONCAT('ある一日の終了済み接続セッション。セッション数=', COUNT(*), '。行動や個人貢献は不明。')
        FROM T_CK_SESSION WHERE disconnected_at IS NOT NULL
        GROUP BY ck_player_id, CAST(connected_at AT TIME ZONE 'Asia/Tokyo' AS DATE)
        UNION ALL
        SELECT w.game_id, CONCAT('world:', w.game_world_id),
          CONCAT('world:', w.game_world_id, ':', s.source_sha256),
          CAST(MIN(s.captured_at) AT TIME ZONE 'Asia/Tokyo' AS DATE),
          CONCAT('検証済みのゲーム世界の保存状態。達成が確認された進捗項目数=',
          MAX((SELECT COUNT(*) FROM T_PROGRESS_STATE p WHERE p.world_snapshot_id = s.world_snapshot_id AND p.state = 'ACHIEVED')),
          '。所有者・個人貢献・過去の納品履歴は不明。世界由来の人物として創作する。')
        FROM T_WORLD_SNAPSHOT s JOIN M_GAME_WORLD w ON w.game_world_id = s.game_world_id
        WHERE s.validation_status = 'VALID' AND w.game_id IN ('terraria', 'corekeeper')
        GROUP BY w.game_id, w.game_world_id, s.source_sha256
      ), unused AS (
        SELECT * FROM candidates c WHERE source_date < ? AND NOT EXISTS
          (SELECT 1 FROM T_GUILD_SOURCE used WHERE used.source_key = c.source_key)
      )
      """;

  public Optional<Source> choose(LocalDate today) {
    boolean recent = ThreadLocalRandom.current().nextDouble() < properties.recentRatio();
    Optional<Source> source = choose(today, recent);
    return source.isPresent() ? source : choose(today, !recent);
  }

  private Optional<Source> choose(LocalDate today, boolean recent) {
    String pool = CANDIDATES + ", pool AS (SELECT * FROM unused WHERE source_date "
        + (recent ? ">=" : "<") + " ?) ";
    LocalDate cutoff = today.minusDays(properties.recentDays());
    List<String> games = jdbc.queryForList(pool + "SELECT DISTINCT game FROM pool", String.class, today, cutoff);
    if (games.isEmpty()) return Optional.empty();
    String game = games.get(ThreadLocalRandom.current().nextInt(games.size()));
    String owner = jdbc.queryForObject(pool + "SELECT owner FROM (SELECT DISTINCT owner FROM pool WHERE game = ?) owners ORDER BY RANDOM() LIMIT 1",
        String.class, today, cutoff, game);
    return jdbc.query(pool + "SELECT source_key, game, facts FROM pool WHERE game = ? AND owner = ? ORDER BY RANDOM() LIMIT 1",
        (rs, row) -> new Source(rs.getString(1), rs.getString(2), rs.getString(3)), today, cutoff, game, owner).stream().findFirst();
  }
}
