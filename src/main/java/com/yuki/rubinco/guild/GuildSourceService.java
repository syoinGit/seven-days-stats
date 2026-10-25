package com.yuki.rubinco.guild;

import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.octarine.names.CoreKeeperNames;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A resident is born from one "memory": up to ten records from a random few hours of a random
 * past day in one game. Records never carry player names or ids, only what happened
 * ("誰かがやって来た", "ゾンビを倒した", item and enemy names), so the memory can be shown publicly.
 */
@Service
@RequiredArgsConstructor
public class GuildSourceService {
  static final int WINDOW_HOURS = 3;
  static final int MAX_EVENTS = 10;
  private static final int MAX_ROWS = 5000;
  private static final int MAX_PICKS = 20;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");
  static final Map<String, String> GAMES = Map.of(
      "7dtd", "7 Days to Die", "terraria", "Terraria", "corekeeper", "Core Keeper");

  private final JdbcTemplate jdbc;
  private final GuildProperties properties;
  private final TerrariaNames terrariaNames;
  private final CoreKeeperNames coreKeeperNames;

  /** label: "7 Days to Die・10月3日 21時〜24時の記録"; facts: one "21:14 ゾンビを倒した" per line. */
  public record Source(String key, String game, String label, String facts) { }

  private record Event(OffsetDateTime at, String text) { }

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
            WHERE event_type IN ('PLAYER_DEATH', 'BLOOD_MOON') AND occurred_at >= ? AND occurred_at < ?
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

  /** A random unused memory from a day before today; recent days are preferred by recent-ratio. */
  public Optional<Source> choose(LocalDate today) {
    Map<String, List<LocalDate>> days = new TreeMap<>();
    for (String game : EVENTS.keySet()) {
      List<LocalDate> dates = jdbc.queryForList("SELECT DISTINCT CAST(happened_at AT TIME ZONE 'Asia/Tokyo' AS DATE) FROM ("
          + EVENTS.get(game) + ") e", LocalDate.class, range(game, start(LocalDate.of(2000, 1, 1)), start(today)));
      if (!dates.isEmpty()) days.put(game, dates);
    }
    LocalDate cutoff = today.minusDays(properties.recentDays());
    boolean recent = ThreadLocalRandom.current().nextDouble() < properties.recentRatio();
    Optional<Source> source = pick(days, date -> recent == !date.isBefore(cutoff));
    return source.isPresent() ? source : pick(days, date -> true);
  }

  private Optional<Source> pick(Map<String, List<LocalDate>> days, java.util.function.Predicate<LocalDate> wanted) {
    Map<String, List<LocalDate>> pool = new LinkedHashMap<>();
    days.forEach((game, dates) -> {
      List<LocalDate> kept = dates.stream().filter(wanted).toList();
      if (!kept.isEmpty()) pool.put(game, kept);
    });
    List<String> games = new ArrayList<>(pool.keySet());
    for (int attempt = 0; attempt < MAX_PICKS && !games.isEmpty(); attempt++) {
      String game = games.get(ThreadLocalRandom.current().nextInt(games.size()));
      List<LocalDate> dates = pool.get(game);
      Optional<Source> source = memory(game, dates.get(ThreadLocalRandom.current().nextInt(dates.size())));
      if (source.isPresent()) return source;
    }
    return Optional.empty();
  }

  /** One random unused window of the day, with up to ten of its records in time order. */
  Optional<Source> memory(String game, LocalDate date) {
    List<Event> events = jdbc.query("SELECT * FROM (" + EVENTS.get(game) + ") e LIMIT " + MAX_ROWS,
        (rs, row) -> event(game, rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3), rs.getLong(4)),
        range(game, start(date), start(date.plusDays(1)))).stream().filter(e -> e.text() != null).toList();
    Map<Integer, List<Event>> windows = events.stream().collect(Collectors.groupingBy(
        e -> e.at().atZoneSameInstant(GuildService.TOKYO).getHour() / WINDOW_HOURS));
    List<Integer> order = new ArrayList<>(windows.keySet());
    Collections.shuffle(order);
    for (int window : order) {
      String key = "memory:" + game + ":" + date + ":" + window * WINDOW_HOURS;
      if (jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_SOURCE WHERE source_key = ?", Integer.class, key) > 0) continue;
      List<Event> picked = new ArrayList<>(windows.get(window));
      Collections.shuffle(picked);
      picked = picked.stream().limit(MAX_EVENTS).sorted(Comparator.comparing(Event::at)).toList();
      String facts = picked.stream()
          .map(e -> TIME.format(e.at().atZoneSameInstant(GuildService.TOKYO)) + " " + e.text())
          .collect(Collectors.joining("\n"));
      String label = GAMES.get(game) + "・" + DAY.format(date) + " " + window * WINDOW_HOURS + "時〜"
          + (window + 1) * WINDOW_HOURS + "時の記録";
      return Optional.of(new Source(key, game, label, facts.length() > 1000 ? facts.substring(0, 1000) : facts));
    }
    return Optional.empty();
  }

  private Event event(String game, OffsetDateTime at, String kind, String detail, long amount) {
    String text = switch (game + ":" + kind) {
      case "7dtd:kill" -> detail + "を倒した";
      case "7dtd:join" -> "誰かがこの地にやって来た";
      case "7dtd:leave" -> "誰かが旅立った";
      case "7dtd:PLAYER_DEATH" -> "誰かが倒れた";
      case "7dtd:BLOOD_MOON" -> "ブラッドムーンが昇った";
      case "terraria:item" -> change(terrariaNames.item(Integer.parseInt(detail)).label(), amount);
      case "terraria:kill" -> terrariaNames.npc(detail).label() + "を" + amount + "体倒した";
      case "corekeeper:join" -> "誰かが洞窟へ降りてきた";
      case "corekeeper:leave" -> "誰かが地上へ戻った";
      case "corekeeper:item" -> change(coreKeeperNames.object(Integer.parseInt(detail)).label(), amount);
      case "terraria:progress", "corekeeper:progress" -> detail + "を達成した";
      default -> null;
    };
    return new Event(at, text);
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
