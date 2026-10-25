package com.yuki.rubinco.octarine.session;

import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Connected;
import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Disconnected;
import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Event;
import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Renamed;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes connections to M_CK_PLAYER / T_CK_SESSION. The stream re-reads the last hour after a
 * reconnect, so every write is safe to repeat: a connect is stored once per start time, and old
 * lines never move a player's name or last-seen time backwards.
 */
@Component
@RequiredArgsConstructor
public class CoreKeeperSessionStore {
  static final String RECONNECTED = "RECONNECTED";
  static final String SERVER_STOPPED = "SERVER_STOPPED";

  private final JdbcTemplate jdbc;

  @Transactional
  public void apply(Event event) {
    switch (event) {
      case Connected connected -> connected(connected);
      case Renamed renamed -> {
        long player = player(renamed.steamId(), renamed.name(), renamed.at());
        jdbc.update("UPDATE T_CK_SESSION SET player_name = ? WHERE ck_player_id = ? "
            + "AND disconnected_at IS NULL AND connected_at <= ?", renamed.name(), player, ts(renamed.at()));
      }
      case Disconnected disconnected -> {
        Long player = existing(disconnected.steamId());
        if (player == null) return;
        touch(player, null, disconnected.at());
        jdbc.update("UPDATE T_CK_SESSION SET disconnected_at = ?, end_reason = ? WHERE ck_player_id = ? "
            + "AND disconnected_at IS NULL AND connected_at <= ?",
            ts(disconnected.at()), disconnected.reason(), player, ts(disconnected.at()));
      }
    }
  }

  /** Closes every open session, e.g. once the server is seen stopped. Returns how many. */
  @Transactional
  public int closeOpen(OffsetDateTime at, String reason) {
    return jdbc.update("UPDATE T_CK_SESSION SET disconnected_at = CASE WHEN connected_at > ? "
        + "THEN connected_at ELSE ? END, end_reason = ? WHERE disconnected_at IS NULL",
        ts(at), ts(at), reason);
  }

  private void connected(Connected event) {
    long player = player(event.steamId(), event.name(), event.at());
    // The game allows one connection per account; a newer connect ends any session left open.
    jdbc.update("UPDATE T_CK_SESSION SET disconnected_at = ?, end_reason = ? WHERE ck_player_id = ? "
        + "AND disconnected_at IS NULL AND connected_at < ?",
        ts(event.at()), RECONNECTED, player, ts(event.at()));
    Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_SESSION WHERE ck_player_id = ? "
        + "AND connected_at = ?", Integer.class, player, ts(event.at()));
    if (stored != null && stored > 0) return;
    jdbc.update("INSERT INTO T_CK_SESSION (ck_player_id, player_name, connected_at) VALUES (?, ?, ?)",
        player, event.name(), ts(event.at()));
  }

  private long player(String steamId, String name, OffsetDateTime at) {
    Long id = existing(steamId);
    if (id != null) {
      touch(id, name, at);
      return id;
    }
    var key = new GeneratedKeyHolder();
    Long sevenDays = sevenDaysPlayer(steamId);
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("INSERT INTO M_CK_PLAYER "
          + "(steam_id, player_id, player_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
          new String[] {"id"});
      statement.setString(1, steamId);
      statement.setObject(2, sevenDays);
      statement.setString(3, name);
      statement.setTimestamp(4, ts(at));
      statement.setTimestamp(5, ts(at));
      return statement;
    }, key);
    return key.getKey().longValue();
  }

  /** Newer events update the name and last-seen time; the 7DTD link is filled once it exists. */
  private void touch(long id, String name, OffsetDateTime at) {
    record Row(String steamId, Long playerId, Timestamp lastSeen) { }
    Row row = jdbc.queryForObject("SELECT steam_id, player_id, last_seen_at FROM M_CK_PLAYER WHERE id = ?",
        (rs, i) -> new Row(rs.getString(1), rs.getObject(2, Long.class), rs.getTimestamp(3)), id);
    boolean newer = !row.lastSeen().after(ts(at));
    Long sevenDays = row.playerId() == null ? sevenDaysPlayer(row.steamId()) : null;
    if (sevenDays != null) jdbc.update("UPDATE M_CK_PLAYER SET player_id = ? WHERE id = ?", sevenDays, id);
    if (!newer) return;
    if (name == null) {
      jdbc.update("UPDATE M_CK_PLAYER SET last_seen_at = ? WHERE id = ?", ts(at), id);
    } else {
      jdbc.update("UPDATE M_CK_PLAYER SET player_name = ?, last_seen_at = ? WHERE id = ?", name, ts(at), id);
    }
  }

  private Long existing(String steamId) {
    List<Long> ids = jdbc.queryForList("SELECT id FROM M_CK_PLAYER WHERE steam_id = ?", Long.class, steamId);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  /** The 7DTD player master row with this SteamID (as the key, platform id or native id). */
  private Long sevenDaysPlayer(String steamId) {
    String prefixed = "Steam_" + steamId;
    List<Long> ids = jdbc.queryForList("SELECT id FROM M_PLAYER WHERE player_key = ? "
        + "OR (UPPER(platform) = 'STEAM' AND user_id IN (?, ?)) "
        + "OR (UPPER(native_platform) = 'STEAM' AND native_user_id IN (?, ?)) "
        // Like the 7DTD dashboard, prefer the most recently seen row when there are duplicates.
        + "ORDER BY CASE WHEN last_seen_at IS NULL THEN 1 ELSE 0 END, last_seen_at DESC, id DESC",
        Long.class, "Steam:" + steamId, steamId, prefixed, steamId, prefixed);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private static Timestamp ts(OffsetDateTime at) {
    return Timestamp.from(at.toInstant());
  }
}
