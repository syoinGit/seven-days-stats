package com.yuki.rubinco.guild;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The guild's gossip for the traveller (the tavern's "ギルドの噂話"): what happened in the last few days, told in fixed
 * words (no AI) from what is already kept: bonds that changed, residents who moved, joined or left a faction or changed
 * faith, newcomers, and dungeon runs that came back. Nothing is written. A death keeps no date, so it is not told.
 * (What the residents themselves hear of each other is GuildRumors.)
 */
@Service
@RequiredArgsConstructor
public class GuildNews {
  /** How far back the gossip reaches. */
  static final int DAYS = 7;
  /** How many are told at most, newest first. */
  static final int LIMIT = 8;
  /** Bonds in order of closeness: a change toward the start is a step apart. */
  private static final List<String> CLOSENESS = List.of("acquaintance", "friend", "companion", "close_friend", "mentor", "lover", "spouse");

  /** at: when it happened. href: where to read more. */
  public record Item(OffsetDateTime at, String text, String href) {
    public String when() {
      return at.atZoneSameInstant(GuildService.TOKYO).format(java.time.format.DateTimeFormatter.ofPattern("M/d"));
    }

    public long epochSecond() {
      return at.toEpochSecond();
    }
  }

  private final JdbcTemplate jdbc;

  public List<Item> recent(ZonedDateTime now) {
    var until = now.toOffsetDateTime();
    var since = now.minusDays(DAYS).toOffsetDateTime();
    List<Item> items = new ArrayList<>();
    jdbc.query("""
        SELECT i.happened_at, a.name, b.name, e.label_before, e.label_after, i.id
        FROM T_GUILD_RELATION_EVENT e JOIN T_GUILD_INTERACTION i ON i.id = e.interaction_id
        JOIN M_GUILD_CHARACTER a ON a.id = e.from_id JOIN M_GUILD_CHARACTER b ON b.id = e.to_id
        WHERE e.change_kind = 'label' AND i.happened_at > ? AND i.happened_at <= ? ORDER BY i.happened_at DESC LIMIT ?
        """, rs -> {
      String text = bond(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
      if (text != null) items.add(new Item(rs.getObject(1, OffsetDateTime.class), text, "/guild/log/" + rs.getLong(6)));
    }, since, until, LIMIT * 3);
    jdbc.query("""
        SELECT h.changed_at, c.name, h.change_kind, COALESCE(v.name, f.name, t.name), c.id
        FROM T_GUILD_AFFILIATION_HISTORY h JOIN M_GUILD_CHARACTER c ON c.id = h.character_id
        LEFT JOIN M_GUILD_VILLAGE v ON h.kind = 'village' AND v.village_key = h.to_value
        LEFT JOIN M_GUILD_FACTION f ON h.kind = 'faction' AND f.faction_key = h.to_value
        LEFT JOIN M_GUILD_FAITH t ON h.kind = 'faith' AND t.faith_key = h.to_value
        WHERE h.change_kind IN ('birth', 'move', 'join', 'leave', 'expel', 'convert') AND (h.change_kind <> 'birth' OR h.kind = 'village')
          AND h.changed_at > ? AND h.changed_at <= ? ORDER BY h.changed_at DESC LIMIT ?
        """, rs -> {
      String text = affiliation(rs.getString(2), rs.getString(3), rs.getString(4));
      if (text != null) items.add(new Item(rs.getObject(1, OffsetDateTime.class), text, "/guild/characters/" + rs.getLong(5)));
    }, since, until, LIMIT * 3);
    jdbc.query("SELECT returned_at, dungeon_name, result, id FROM T_GUILD_EXPEDITION WHERE returned_at > ? AND returned_at <= ? "
        + "ORDER BY returned_at DESC LIMIT ?", rs -> {
      long id = rs.getLong(4);
      List<String> names = jdbc.queryForList("SELECT c.name FROM T_GUILD_EXPEDITION_MEMBER m JOIN M_GUILD_CHARACTER c ON c.id = m.character_id "
          + "WHERE m.expedition_id = ? ORDER BY m.position", String.class, id);
      items.add(new Item(rs.getObject(1, OffsetDateTime.class), expedition(names, rs.getString(2), rs.getString(3)), "/guild/dungeon/" + id));
    }, since, until, LIMIT);
    return items.stream().sorted(Comparator.comparing(Item::at).reversed()).limit(LIMIT).toList();
  }

  /** A bond that changed, in words; null for meeting someone for the first time (not worth telling). */
  static String bond(String a, String b, String before, String after) {
    String two = a + "と" + b;
    if (after == null || after.equals("acquaintance")) return before == null || before.equals("acquaintance") ? null : two + "は、もう" + label(before) + "ではないらしい。";
    return switch (after) {
      case "spouse" -> two + "が結ばれたそうだ。";
      case "lover" -> two + "が恋人になったらしい。";
      case "rival" -> two + "が宿敵になったらしい。";
      case "mentor" -> two + "が師弟になったそうだ。";
      default -> before != null && CLOSENESS.indexOf(after) < CLOSENESS.indexOf(before)
          ? two + "の仲が冷えて、" + label(after) + "に戻ったらしい。"
          : two + "が" + label(after) + "になったらしい。";
    };
  }

  /** A move, a faction or a faith, in words; to: the name of what it became. */
  static String affiliation(String name, String change, String to) {
    return switch (change) {
      case "birth" -> "見慣れない顔がいる。" + name + "というらしい。";
      case "move" -> name + "が" + to + "へ移り住んだそうだ。";
      case "join" -> name + "が" + to + "に加わったらしい。";
      case "leave" -> name + "が派閥を抜けたらしい。";
      case "expel" -> name + "が派閥を追われたそうだ。";
      case "convert" -> name + "が" + to + "に改宗したらしい。";
      default -> null;
    };
  }

  static String expedition(List<String> party, String dungeon, String result) {
    String who = party.isEmpty() ? "冒険者たち" : String.join("と", party);
    return switch (result) {
      case "success" -> who + "が" + dungeon + "を踏破して帰ってきたそうだ。";
      case "partial" -> who + "が" + dungeon + "から戻った。目的は半ばまで果たしたらしい。";
      default -> who + "は" + dungeon + "から引き返してきたそうだ。";
    };
  }

  private static String label(String key) {
    return GuildInteractionService.LABELS.getOrDefault(key, key);
  }
}
