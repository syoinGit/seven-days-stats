package com.yuki.sevendays_states.web;

import com.yuki.sevendays_states.service.AiCommentService;
import com.yuki.sevendays_states.util.DisplayTimeFormatter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DiaryMaintenanceService {

  private static final Pattern BLOOD_MOON_DAY = Pattern.compile("(?i)\\bDay\\s+(\\d+)\\b");

  private final JdbcTemplate jdbcTemplate;
  private final AiCommentService aiCommentService;
  private final PoiNameService poiNameService;
  private final DiaryPromptBuilder diaryPromptBuilder;

  public List<DiaryDay> days() {
    List<OffsetDateTime> timestamps = jdbcTemplate.query("""
        select occurred_at from (
          select occurred_at from t_player_join_transaction
          union all select occurred_at from t_player_leave_transaction
          union all select occurred_at from t_entity_kill_transaction
          union all select occurred_at from t_sleeper_transaction where transaction_type = 'SLEEPER_SPAWN'
          union all select occurred_at from t_world_event_transaction where event_type <> 'BLOOD_MOON'
          union all select occurred_at from t_vehicle_position_transaction
          where movement_valid = true and attributed_player_id is not null and movement_distance >= 1
          union all select occurred_at from t_level_xp_summary_transaction
          union all select observed_at as occurred_at from t_world_time_observation
        ) diary_events
        order by occurred_at desc
        limit 10000
        """, (rs, rowNum) -> rs.getObject("occurred_at", OffsetDateTime.class));
    LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
    timestamps.forEach(timestamp -> dates.add(timestamp.atZoneSameInstant(DisplayTimeFormatter.JST).toLocalDate()));
    aiCommentService.diaries().stream()
        .map(AiCommentService.AiCommentEntry::diaryDate)
        .filter(java.util.Objects::nonNull)
        .forEach(dates::add);
    return dates.stream().sorted(java.util.Comparator.reverseOrder()).limit(30)
        .map(date -> {
          DiaryPacket packet = packet(date);
          return new DiaryDay(
              date, packet.gameDayLabel(), packet.participants().size(), packet.eventCount(),
              aiCommentService.findByDiaryDate(date).isPresent());
        }).toList();
  }

  public DiaryPacket packet(LocalDate date) {
    OffsetDateTime from = date.atStartOfDay(DisplayTimeFormatter.JST).toOffsetDateTime();
    OffsetDateTime to = date.plusDays(1).atStartOfDay(DisplayTimeFormatter.JST).toOffsetDateTime();
    List<WorldClock> clocks = jdbcTemplate.query("""
        select game_day, game_hour, game_minute
        from t_world_time_observation
        where observed_at >= ? and observed_at < ?
        order by observed_at
        """, (rs, rowNum) -> new WorldClock(
        rs.getInt("game_day"), rs.getInt("game_hour"), rs.getInt("game_minute")), from, to);
    String gameDayLabel = clocks.isEmpty() ? "未観測" : gameDayLabel(clocks.getFirst(), clocks.getLast());

    List<String> playerNames = jdbcTemplate.queryForList("""
        select distinct player_name from (
          select player_name from t_player_join_transaction where occurred_at >= ? and occurred_at < ?
          union select player_name from t_player_leave_transaction where occurred_at >= ? and occurred_at < ?
          union select player_name from t_player_position_transaction where occurred_at >= ? and occurred_at < ?
          union select player_name from t_entity_kill_transaction where occurred_at >= ? and occurred_at < ?
          union select player_name from t_sleeper_transaction where occurred_at >= ? and occurred_at < ?
        ) players where player_name is not null and player_name <> '' order by player_name
        """, String.class, from, to, from, to, from, to, from, to, from, to);
    List<PlayerDay> participants = playerNames.stream()
        .map(name -> playerDay(name, from, to))
        .toList();

    List<EventCount> events = jdbcTemplate.query("""
        select event_type, count(*) as event_count from (
          select event_type from t_world_event_transaction
          where occurred_at >= ? and occurred_at < ? and event_type <> 'BLOOD_MOON'
          union all
          select 'SLEEPER_SPAWN' from t_sleeper_transaction
          where occurred_at >= ? and occurred_at < ? and transaction_type = 'SLEEPER_SPAWN'
        ) events group by event_type order by event_count desc, event_type
        """, (rs, rowNum) -> new EventCount(rs.getString("event_type"), rs.getLong("event_count")),
        from, to, from, to);
    List<EventCount> enemies = jdbcTemplate.query("""
        select coalesce((select display_text from m_japanese_translation tr
                         where tr.localization_key = k.target_entity_type limit 1),
                        k.target_entity_type) as event_type,
               count(*) as event_count
        from t_entity_kill_transaction k
        where occurred_at >= ? and occurred_at < ?
        group by k.target_entity_type
        order by event_count desc
        limit 10
        """, (rs, rowNum) -> new EventCount(rs.getString("event_type"), rs.getLong("event_count")), from, to);
    List<String> pois = jdbcTemplate.queryForList("""
        select distinct poi_name from (
          select (select poi.poi_name from m_world_poi poi
                  where coalesce(poi.category, '') <> 'part' and poi.poi_name not like 'part_%'
                  order by ((poi.x - pos.position_x) * (poi.x - pos.position_x)
                       + (poi.z - pos.position_z) * (poi.z - pos.position_z)) limit 1) as poi_name
          from t_player_position_transaction pos
          where pos.occurred_at >= ? and pos.occurred_at < ?
        ) visited where poi_name is not null limit 30
        """, String.class, from, to).stream().map(poiNameService::displayName).toList();

    Long countedEvents = jdbcTemplate.queryForObject("""
        select count(*) from (
          select occurred_at from t_player_join_transaction where occurred_at >= ? and occurred_at < ?
          union all select occurred_at from t_player_leave_transaction where occurred_at >= ? and occurred_at < ?
          union all select occurred_at from t_entity_kill_transaction where occurred_at >= ? and occurred_at < ?
          union all select occurred_at from t_sleeper_transaction where occurred_at >= ? and occurred_at < ? and transaction_type = 'SLEEPER_SPAWN'
          union all select occurred_at from t_world_event_transaction where occurred_at >= ? and occurred_at < ? and event_type <> 'BLOOD_MOON'
          union all select occurred_at from t_vehicle_position_transaction
          where occurred_at >= ? and occurred_at < ?
            and movement_valid = true and attributed_player_id is not null and movement_distance >= 1
        ) counted
        """, Long.class, from, to, from, to, from, to, from, to, from, to, from, to);
    long eventCount = countedEvents == null ? 0 : countedEvents;
    Optional<AiCommentService.AiCommentEntry> diary = aiCommentService.findByDiaryDate(date);
    BloodMoonContext bloodMoon = bloodMoonContext(to, clocks);
    XpSummary xp = xpSummary(from, to);
    String generationData = diaryPromptBuilder.build(
        date, gameDayLabel, participants, events, enemies, pois, bloodMoon, xp);
    return new DiaryPacket(date, gameDayLabel, clocks.isEmpty() ? "未観測" : clockLabel(clocks.getLast()),
        eventCount, participants, events, enemies, pois, bloodMoon, xp, generationData,
        diary.orElse(null));
  }

  private PlayerDay playerDay(String name, OffsetDateTime from, OffsetDateTime to) {
    long joins = count("t_player_join_transaction", name, from, to, null);
    long leaves = count("t_player_leave_transaction", name, from, to, null);
    long kills = count("t_entity_kill_transaction", name, from, to, null);
    long encounters = count("t_sleeper_transaction", name, from, to, "transaction_type = 'SLEEPER_SPAWN'");
    BigDecimal position = distance("t_player_position_transaction", "player_name", name, from, to);
    BigDecimal vehicle = jdbcTemplate.queryForObject("""
        select coalesce(sum(v.movement_distance), 0)
        from t_vehicle_position_transaction v
        join m_player p on p.id = v.attributed_player_id
        where p.player_name = ? and v.occurred_at >= ? and v.occurred_at < ?
          and v.movement_valid = true and v.movement_distance > 0
        """, BigDecimal.class, name, from, to);
    return new PlayerDay(
        name, joins, leaves, kills, encounters, position,
        vehicle == null ? BigDecimal.ZERO : vehicle,
        observedPlace(name, from, to, true), observedPlace(name, from, to, false));
  }

  private XpSummary xpSummary(OffsetDateTime from, OffsetDateTime to) {
    return jdbcTemplate.queryForObject("""
        select coalesce(sum(xp_total), 0) as total,
               coalesce(sum(xp_from_kill), 0) as kills,
               coalesce(sum(xp_from_loot), 0) as loot,
               coalesce(sum(xp_from_harvesting), 0) as harvest,
               count(*) as reports
        from t_level_xp_summary_transaction
        where occurred_at >= ? and occurred_at < ?
        """, (rs, rowNum) -> new XpSummary(
        rs.getLong("total"), rs.getLong("kills"), rs.getLong("loot"),
        rs.getLong("harvest"), rs.getLong("reports")), from, to);
  }

  private String observedPlace(
      String name, OffsetDateTime from, OffsetDateTime to, boolean first) {
    List<ObservedPlace> places = jdbcTemplate.query("""
        select position_x, position_y, position_z,
               (select poi.poi_name from m_world_poi poi
                where coalesce(poi.category, '') <> 'part' and poi.poi_name not like 'part_%%'
                order by ((poi.x - pos.position_x) * (poi.x - pos.position_x)
                     + (poi.z - pos.position_z) * (poi.z - pos.position_z)) limit 1) as poi_name
        from t_player_position_transaction pos
        where player_name = ? and occurred_at >= ? and occurred_at < ?
        order by occurred_at %s
        limit 1
        """.formatted(first ? "asc" : "desc"), (rs, rowNum) -> new ObservedPlace(
        rs.getInt("position_x"), rs.getObject("position_y", Integer.class),
        rs.getInt("position_z"), rs.getString("poi_name")), name, from, to);
    if (places.isEmpty()) {
      return "未観測";
    }
    ObservedPlace place = places.getFirst();
    String coordinate = place.x() + ", " + (place.y() == null ? "?" : place.y()) + ", " + place.z();
    return place.poiName() == null
        ? "座標 " + coordinate
        : poiNameService.displayName(place.poiName()) + "（" + coordinate + "）";
  }

  private BloodMoonContext bloodMoonContext(OffsetDateTime to, List<WorldClock> clocks) {
    List<String> schedules = jdbcTemplate.queryForList("""
        select detail_text from t_world_event_transaction
        where event_type = 'BLOOD_MOON' and occurred_at < ?
        order by occurred_at desc limit 1
        """, String.class, to);
    if (schedules.isEmpty() || clocks.isEmpty()) {
      return new BloodMoonContext("未観測", false);
    }
    Matcher matcher = BLOOD_MOON_DAY.matcher(schedules.getFirst());
    if (!matcher.find()) {
      return new BloodMoonContext(schedules.getFirst(), false);
    }
    int scheduledDay = Integer.parseInt(matcher.group(1));
    int currentDay = clocks.getLast().day();
    int difference = scheduledDay - currentDay;
    String status = switch (difference) {
      case 0 -> "Blood Moon 当日（Day " + scheduledDay + "）";
      case 1 -> "Blood Moonまであと1日（Day " + scheduledDay + "）";
      case -1 -> "Blood Moon翌日（Day " + scheduledDay + "を通過）";
      default -> difference > 1
          ? "Blood Moonまであと" + difference + "日（Day " + scheduledDay + "）"
          : "Blood Moon Day " + scheduledDay + "を通過";
    };
    return new BloodMoonContext(status, difference == 0);
  }

  private long count(String table, String player, OffsetDateTime from, OffsetDateTime to, String extra) {
    String sql = "select count(*) from " + table
        + " where player_name = ? and occurred_at >= ? and occurred_at < ?"
        + (extra == null ? "" : " and " + extra);
    Long count = jdbcTemplate.queryForObject(sql, Long.class, player, from, to);
    return count == null ? 0 : count;
  }

  private BigDecimal distance(
      String table, String playerColumn, String player, OffsetDateTime from, OffsetDateTime to) {
    BigDecimal distance = jdbcTemplate.queryForObject(
        "select coalesce(sum(movement_distance), 0) from " + table
            + " where " + playerColumn + " = ? and occurred_at >= ? and occurred_at < ?",
        BigDecimal.class, player, from, to);
    return distance == null ? BigDecimal.ZERO : distance;
  }

  private String gameDayLabel(WorldClock first, WorldClock last) {
    return first.day() == last.day()
        ? "DAY " + first.day()
        : "DAY " + first.day() + " → DAY " + last.day();
  }

  private String clockLabel(WorldClock clock) {
    return "DAY %d %02d:%02d".formatted(clock.day(), clock.hour(), clock.minute());
  }

  public record DiaryDay(
      LocalDate date, String gameDayLabel, int participantCount, long eventCount, boolean registered) {
  }

  public record DiaryPacket(
      LocalDate date, String gameDayLabel, String lastWorldTime, long eventCount,
      List<PlayerDay> participants, List<EventCount> events, List<EventCount> enemies,
      List<String> pois, BloodMoonContext bloodMoon, XpSummary xp, String generationData,
      AiCommentService.AiCommentEntry diary) {
  }

  public record PlayerDay(
      String name, long joins, long leaves, long kills, long encounters,
      BigDecimal positionDistance, BigDecimal vehicleDistance,
      String startPlace, String endPlace) {
  }

  public record XpSummary(long total, long kills, long loot, long harvest, long reports) {
  }

  public record BloodMoonContext(String status, boolean today) {
  }

  public record EventCount(String name, long count) {
  }

  private record WorldClock(int day, int hour, int minute) {
  }

  private record ObservedPlace(int x, Integer y, int z, String poiName) {
  }
}
