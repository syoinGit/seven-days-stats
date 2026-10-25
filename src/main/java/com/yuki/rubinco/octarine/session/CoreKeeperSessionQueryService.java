package com.yuki.rubinco.octarine.session;

import com.yuki.rubinco.octarine.ingest.CoreKeeperImportProperties;
import com.yuki.rubinco.portal.service.OnlinePlayerSource;
import com.yuki.rubinco.shared.DisplayTimeFormatter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Connections and play time for the pages. Names only: the SteamID never leaves this class except
 * as its last four digits for admins.
 */
@Service
@RequiredArgsConstructor
public class CoreKeeperSessionQueryService implements OnlinePlayerSource {
  private static final DisplayTimeFormatter TIME = new DisplayTimeFormatter();
  private static final String GAME_ID = "corekeeper";

  private final JdbcTemplate jdbc;
  private final CoreKeeperImportProperties properties;
  private final Clock clock = Clock.systemUTC();

  public record Online(String name, String sinceLabel, String duration) { }

  public record PlayerTotal(long id, String name, String total, long minutes, String week,
      int sessions, String lastPlayed, boolean online) { }

  public record Session(String name, String fromLabel, String toLabel, String duration, boolean ongoing) { }

  public record AccountPlayTime(String name, String total, String week, int sessions, String lastPlayed,
      boolean online) { }

  public record AdminPlayer(long id, String name, String steamTail, Long webAccountId, Long playerId,
      String total, String lastSeen) { }

  /** False until the log stream is turned on, so pages can say so instead of showing nobody. */
  public boolean tracking() {
    return properties.isLogStreamEnabled();
  }

  @Override
  public String gameId() {
    return GAME_ID;
  }

  @Override
  public int onlineCount() {
    if (!tracking()) return 0;
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(DISTINCT ck_player_id) FROM T_CK_SESSION WHERE disconnected_at IS NULL", Integer.class);
    return count == null ? 0 : count;
  }

  public List<Online> online() {
    if (!tracking()) return List.of();
    OffsetDateTime now = now();
    return jdbc.query("SELECT p.player_name, MIN(s.connected_at) FROM T_CK_SESSION s "
        + "JOIN M_CK_PLAYER p ON p.id = s.ck_player_id WHERE s.disconnected_at IS NULL "
        + "GROUP BY p.id, p.player_name ORDER BY MIN(s.connected_at)",
        (rs, i) -> {
          OffsetDateTime since = time(rs.getTimestamp(2));
          return new Online(rs.getString(1), TIME.format(since), duration(Duration.between(since, now)));
        });
  }

  /** Every player, longest total first. Open sessions count up to now. */
  public List<PlayerTotal> players() {
    OffsetDateTime now = now();
    OffsetDateTime weekAgo = now.minusDays(7);
    Map<Long, Totals> totals = totals(null, now, weekAgo);
    return totals.values().stream()
        .sorted(Comparator.comparing((Totals t) -> t.total).reversed().thenComparing(t -> t.name))
        .map(t -> new PlayerTotal(t.id, t.name, duration(t.total), t.total.toMinutes(), duration(t.week),
            t.sessions, t.last == null ? "" : TIME.format(t.last), t.online))
        .toList();
  }

  public List<Session> recent(int limit) {
    OffsetDateTime now = now();
    return jdbc.query("SELECT s.player_name, s.connected_at, s.disconnected_at FROM T_CK_SESSION s "
        + "ORDER BY s.connected_at DESC, s.id DESC LIMIT ?",
        (rs, i) -> {
          OffsetDateTime from = time(rs.getTimestamp(2));
          OffsetDateTime to = time(rs.getTimestamp(3));
          return new Session(rs.getString(1), TIME.format(from), to == null ? "接続中" : TIME.format(to),
              duration(Duration.between(from, to == null ? now : to)), to == null);
        }, limit);
  }

  /**
   * Play time for a portal account: Core Keeper players an admin linked to it, or whose SteamID
   * matches the account's 7DTD player.
   */
  public List<AccountPlayTime> forAccount(Long accountId, Long playerId) {
    if (accountId == null && playerId == null) return List.of();
    List<Object> args = new ArrayList<>(List.of(accountId == null ? -1L : accountId,
        playerId == null ? -1L : playerId));
    StringBuilder sql = new StringBuilder(
        "SELECT id FROM M_CK_PLAYER WHERE web_account_id = ? OR player_id = ?");
    // 7DTD can hold the same member in several rows; match the SteamID, not only the stored row.
    List<String> steamIds = playerId == null ? List.of() : steamIds(playerId);
    for (String steamId : steamIds) {
      sql.append(" OR steam_id = ?");
      args.add(steamId);
    }
    List<Long> ids = jdbc.queryForList(sql.append(" ORDER BY id").toString(), Long.class, args.toArray());
    if (ids.isEmpty()) return List.of();
    OffsetDateTime now = now();
    List<AccountPlayTime> result = new ArrayList<>();
    for (long id : ids) {
      Totals t = totals(id, now, now.minusDays(7)).get(id);
      if (t != null) {
        result.add(new AccountPlayTime(t.name, duration(t.total), duration(t.week), t.sessions,
            t.last == null ? "" : TIME.format(t.last), t.online));
      }
    }
    return result;
  }

  private List<String> steamIds(long playerId) {
    List<String> ids = new ArrayList<>();
    jdbc.query("SELECT player_key, platform, user_id, native_platform, native_user_id FROM M_PLAYER WHERE id = ?",
        rs -> {
          String key = rs.getString(1);
          if (key != null && key.startsWith("Steam:")) ids.add(key.substring("Steam:".length()));
          if ("STEAM".equalsIgnoreCase(rs.getString(2))) ids.add(stripSteam(rs.getString(3)));
          if ("STEAM".equalsIgnoreCase(rs.getString(4))) ids.add(stripSteam(rs.getString(5)));
        }, playerId);
    return ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
  }

  private static String stripSteam(String id) {
    return id != null && id.startsWith("Steam_") ? id.substring("Steam_".length()) : id;
  }

  /** For the admin account page: the SteamID is reduced to its last four digits here. */
  public List<AdminPlayer> adminPlayers() {
    OffsetDateTime now = now();
    Map<Long, Totals> totals = totals(null, now, now.minusDays(7));
    return jdbc.query("SELECT id, player_name, steam_id, web_account_id, player_id, last_seen_at "
        + "FROM M_CK_PLAYER ORDER BY player_name, id",
        (rs, i) -> {
          String steam = rs.getString(3);
          Totals t = totals.get(rs.getLong(1));
          return new AdminPlayer(rs.getLong(1), rs.getString(2),
              "…" + steam.substring(Math.max(0, steam.length() - 4)),
              rs.getObject(4, Long.class), rs.getObject(5, Long.class),
              t == null ? duration(Duration.ZERO) : duration(t.total), TIME.format(time(rs.getTimestamp(6))));
        });
  }

  /** Facts for the world AI: names and hours, nothing that identifies an account. */
  public Optional<String> aiFacts() {
    if (!tracking()) return Optional.empty();
    List<PlayerTotal> players = players();
    if (players.isEmpty()) return Optional.empty();
    List<String> lines = new ArrayList<>();
    List<Online> online = online();
    lines.add(online.isEmpty() ? "いま接続中のプレイヤー: なし"
        : "いま接続中のプレイヤー: " + String.join("、", online.stream()
            .map(o -> o.name() + "（" + o.duration() + "前から）").toList()));
    lines.add("プレイ時間（合計 / 直近7日）: " + String.join("、", players.stream().limit(10)
        .map(p -> p.name() + " " + p.total() + " / " + p.week()).toList()));
    return Optional.of(String.join("\n", lines));
  }

  private Map<Long, Totals> totals(Long onlyPlayer, OffsetDateTime now, OffsetDateTime weekAgo) {
    Map<Long, Totals> totals = new LinkedHashMap<>();
    String where = onlyPlayer == null ? "" : " WHERE p.id = ?";
    Object[] args = onlyPlayer == null ? new Object[0] : new Object[] {onlyPlayer};
    jdbc.query("SELECT p.id, p.player_name, s.connected_at, s.disconnected_at FROM M_CK_PLAYER p "
        + "LEFT JOIN T_CK_SESSION s ON s.ck_player_id = p.id" + where, rs -> {
          long id = rs.getLong(1);
          Totals t = totals.computeIfAbsent(id, key -> new Totals(key));
          t.name = rs.getString(2);
          OffsetDateTime from = time(rs.getTimestamp(3));
          if (from == null) return;
          OffsetDateTime to = time(rs.getTimestamp(4));
          if (to == null) t.online = true;
          OffsetDateTime end = to == null ? now : to;
          if (end.isBefore(from)) return;
          t.total = t.total.plus(Duration.between(from, end));
          if (end.isAfter(weekAgo)) {
            t.week = t.week.plus(Duration.between(from.isAfter(weekAgo) ? from : weekAgo, end));
          }
          t.sessions++;
          if (t.last == null || end.isAfter(t.last)) t.last = end;
        }, args);
    return totals;
  }

  /** "3時間25分", "12分", "1分未満". */
  static String duration(Duration duration) {
    long minutes = Math.max(0, duration.toMinutes());
    if (minutes == 0) return duration.isZero() ? "0分" : "1分未満";
    long hours = minutes / 60;
    if (hours == 0) return minutes + "分";
    return minutes % 60 == 0 ? hours + "時間" : hours + "時間" + (minutes % 60) + "分";
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private static OffsetDateTime time(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
  }

  private static final class Totals {
    final long id;
    String name;
    Duration total = Duration.ZERO;
    Duration week = Duration.ZERO;
    int sessions;
    OffsetDateTime last;
    boolean online;

    Totals(long id) {
      this.id = id;
    }
  }
}
