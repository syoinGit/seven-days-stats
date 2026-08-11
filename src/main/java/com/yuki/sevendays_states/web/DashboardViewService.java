package com.yuki.sevendays_states.web;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import com.yuki.sevendays_states.service.AiCommentService;
import com.yuki.sevendays_states.service.PlayerStatusCatalog;
import com.yuki.sevendays_states.util.DisplayTimeFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardViewService {

  private static final Pattern BLOOD_MOON_DAY = Pattern.compile("(?i)\\bDay\\s+(\\d+)\\b");

  private final JdbcTemplate jdbcTemplate;
  private final SevenDaysDataProperties properties;
  private final AiCommentService aiCommentService;
  private final PoiNameService poiNameService;
  private final EventMessageFormatter eventMessageFormatter;
  private final DisplayTimeFormatter displayTimeFormatter = new DisplayTimeFormatter();

  public DashboardView dashboard() {
    return dashboard(true);
  }

  /** Legacy event projection is retained for reporting screens/tests; the live feed has its own read model. */
  public DashboardView dashboard(boolean includeLegacyTimeline) {
    List<PlayerStatus> playerStatuses = playerStatuses();
    List<TravelEntry> travelEntries = includeLegacyTimeline ? travelEntries() : List.of();
    List<VehicleStatus> vehicleStatuses = vehicleStatuses();
    ServerState serverState = withOnlinePlayerCount(latestServerState());
    WorldTimeStatus worldTime = latestWorldTime();
    return new DashboardView(
        playerStatuses,
        travelEntries,
        vehicleStatuses,
        serverState,
        worldTime,
        latestBloodMoon(worldTime),
        dailyDiary());
  }

  private List<PlayerStatus> playerStatuses() {
    OffsetDateTime currentStateFreshAfter = OffsetDateTime.now(ZoneOffset.UTC)
        .minus(properties.transaction().currentStateMaxAge());
    return jdbcTemplate.query("""
        with player_identity as (
          select p.*,
                 case
                   when upper(p.platform) = 'EOS' then 'EOS:' || replace(p.user_id, 'EOS_', '')
                   when p.user_id like 'EOS_%' then 'EOS:' || replace(p.user_id, 'EOS_', '')
                   when upper(coalesce(p.native_platform, '')) = 'EOS' then 'EOS:' || replace(p.native_user_id, 'EOS_', '')
                   when p.native_user_id like 'EOS_%' then 'EOS:' || replace(p.native_user_id, 'EOS_', '')
                 end as eos_key,
                 case
                   when upper(p.platform) = 'STEAM' then 'Steam:' || replace(p.user_id, 'Steam_', '')
                   when p.user_id like 'Steam_%' then 'Steam:' || replace(p.user_id, 'Steam_', '')
                   when upper(coalesce(p.native_platform, '')) = 'STEAM' then 'Steam:' || replace(p.native_user_id, 'Steam_', '')
                   when p.native_user_id like 'Steam_%' then 'Steam:' || replace(p.native_user_id, 'Steam_', '')
                 end as steam_key
          from m_player p
        ),
        deduped_players as (
          select p.*
          from player_identity p
          where not exists (
            select 1
            from player_identity newer
            where newer.id <> p.id
              and (
                (p.eos_key is not null and p.eos_key = newer.eos_key)
                or (p.steam_key is not null and p.steam_key = newer.steam_key)
                or p.player_key = newer.player_key
              )
              and (
                coalesce(newer.last_seen_at, timestamp '0001-01-01 00:00:00')
                  > coalesce(p.last_seen_at, timestamp '0001-01-01 00:00:00')
                or (
                  coalesce(newer.last_seen_at, timestamp '0001-01-01 00:00:00')
                    = coalesce(p.last_seen_at, timestamp '0001-01-01 00:00:00')
                  and newer.id > p.id
                )
              )
          )
        ),
        latest_snapshot as (
          select *
          from (
            select s.*,
                   row_number() over (partition by player_id order by captured_at desc, id desc) as snapshot_rank
            from t_player_state_snapshot s
          ) ranked_snapshot
          where snapshot_rank = 1
        ),
        latest_current_state as (
          select *
          from (
            select c.*,
                   case
                     when c.cross_platform_id is not null and c.cross_platform_id <> '' then 'EOS:' || replace(c.cross_platform_id, 'EOS_', '')
                     when c.platform_id is not null and c.platform_id <> '' then 'Steam:' || replace(c.platform_id, 'Steam_', '')
                     else 'ENTITY:' || c.player_entity_id
                   end as state_player_key,
                   row_number() over (
                     partition by coalesce(
                       'PLAYER:' || c.player_id,
                       case
                         when c.cross_platform_id is not null and c.cross_platform_id <> '' then 'EOS:' || replace(c.cross_platform_id, 'EOS_', '')
                         when c.platform_id is not null and c.platform_id <> '' then 'Steam:' || replace(c.platform_id, 'Steam_', '')
                         else 'ENTITY:' || c.player_entity_id
                       end
                     )
                     order by c.last_updated desc, c.online desc
                   ) as state_rank
            from t_player_current_state c
          ) ranked_state
          where state_rank = 1
        ),
        latest_position as (
          select player_id, player_name, occurred_at, position_x, position_y, position_z
          from (
            select player_id, player_name, occurred_at, position_x, position_y, position_z,
                   row_number() over (
                     partition by coalesce('PLAYER:' || player_id, player_name)
                     order by occurred_at desc
                   ) as position_rank
            from t_player_position_transaction
          ) ranked_position
          where position_rank = 1
        ),
        stationary_players as (
          -- A player is exploring after remaining within 20m for at least three minutes.
          -- The ten-minute lower bound tolerates missed polling samples without using stale sessions.
          select lp.player_id
          from latest_position lp
          where lp.player_id is not null
            and exists (
              select 1
              from t_player_position_transaction older
              where older.player_id = lp.player_id
                and older.occurred_at between lp.occurred_at - interval '10' minute
                                              and lp.occurred_at - interval '3' minute
                and ((older.position_x - lp.position_x) * (older.position_x - lp.position_x)
                  + (older.position_z - lp.position_z) * (older.position_z - lp.position_z)) <= 400
            )
            and not exists (
              select 1
              from t_player_position_transaction recent
              where recent.player_id = lp.player_id
                and recent.occurred_at between lp.occurred_at - interval '3' minute
                                               and lp.occurred_at
                and ((recent.position_x - lp.position_x) * (recent.position_x - lp.position_x)
                  + (recent.position_z - lp.position_z) * (recent.position_z - lp.position_z)) > 400
            )
        ),
        status_rows as (
          select p.id as player_id,
                 p.player_name,
                 s.world_name,
                 s.game_name,
                 coalesce(c.last_updated, pp.occurred_at, s.last_login) as last_login,
                 coalesce(c.position_x, pp.position_x, s.x) as x,
                 coalesce(c.position_y, pp.position_y, s.y) as y,
                 coalesce(c.position_z, pp.position_z, s.z) as z,
                 c.health,
                 c.deaths,
                 c.level,
                 c.ping,
                 (
                   select coalesce(sum(d.movement_distance), 0)
                   from t_player_position_transaction d
                   where d.player_id = p.id and d.movement_mode = 'ON_FOOT'
                 ) as travel_distance,
                 (
                   select coalesce(sum(v.movement_distance), 0)
                   from t_vehicle_position_transaction v
                   where v.attributed_player_id = p.id and v.movement_valid = true
                 ) as vehicle_distance,
                 (
                   select coalesce(v.vehicle_name, v.vehicle_type)
                   from t_vehicle_current_state v
                   where v.owner_player_id = p.id and v.active = true
                     and c.position_x is not null and c.position_z is not null
                     and ((v.position_x - c.position_x) * (v.position_x - c.position_x)
                       + (v.position_z - c.position_z) * (v.position_z - c.position_z)) <= 64
                   order by v.last_updated desc
                   limit 1
                 ) as current_vehicle,
                 case
                   when c.online = true and c.last_updated >= ? then true
                   else false
                 end as online,
                 case when stationary.player_id is not null then true else false end as exploring,
                 (
                   select poi.poi_name
                   from m_world_poi poi
                   where coalesce(poi.category, '') <> 'part'
                     and poi.poi_name not like 'part_%'
                   order by ((poi.x - coalesce(c.position_x, pp.position_x, s.x)) * (poi.x - coalesce(c.position_x, pp.position_x, s.x))
                         + (poi.z - coalesce(c.position_z, pp.position_z, s.z)) * (poi.z - coalesce(c.position_z, pp.position_z, s.z)))
                   limit 1
                 ) as poi_name,
                 (
                   select poi.category
                   from m_world_poi poi
                   where coalesce(poi.category, '') <> 'part'
                     and poi.poi_name not like 'part_%'
                   order by ((poi.x - coalesce(c.position_x, pp.position_x, s.x)) * (poi.x - coalesce(c.position_x, pp.position_x, s.x))
                         + (poi.z - coalesce(c.position_z, pp.position_z, s.z)) * (poi.z - coalesce(c.position_z, pp.position_z, s.z)))
                   limit 1
                 ) as poi_category,
                 row_number() over (
                   partition by p.id
                   order by coalesce(c.last_updated, pp.occurred_at, s.captured_at) desc nulls last,
                            c.online desc nulls last
                 ) as card_rank
          from deduped_players p
          left join latest_snapshot s on s.player_id = p.id
          left join latest_current_state c on c.player_id = p.id
              or (c.player_id is null and c.state_player_key in (p.eos_key, p.steam_key, p.player_key))
          left join latest_position pp on pp.player_id = p.id
              or (pp.player_id is null and pp.player_name = p.player_name)
          left join stationary_players stationary on stationary.player_id = p.id
        )
        select player_id, player_name, world_name, game_name, last_login, x, y, z,
               health, deaths, level, ping, travel_distance, vehicle_distance, current_vehicle,
               online, exploring, poi_name, poi_category,
               (select status from t_player_status ps where ps.player_id = sr.player_id) as custom_status
        from status_rows sr
        where card_rank = 1
        order by last_login desc nulls last, player_name
        limit 12
        """, (rs, rowNum) -> new PlayerStatus(
        rs.getLong("player_id"),
        rs.getString("player_name"),
        rs.getString("world_name"),
        rs.getString("game_name"),
        toDisplayTime(rs.getObject("last_login")),
        coordinate(rs.getObject("x"), rs.getObject("y"), rs.getObject("z")),
        displayPoi(rs.getString("poi_name")),
        rs.getString("poi_category"),
        integer(rs, "health"),
        integer(rs, "deaths"),
        integer(rs, "level"),
        integer(rs, "ping"),
        rs.getBigDecimal("travel_distance"),
        rs.getBigDecimal("vehicle_distance"),
        rs.getString("current_vehicle"),
        booleanValue(rs, "online"),
        booleanValue(rs, "exploring"),
        rs.getString("custom_status")), currentStateFreshAfter);
  }

  public Optional<PlayerDetailView> playerDetail(Long playerId) {
    OffsetDateTime currentStateFreshAfter = OffsetDateTime.now(ZoneOffset.UTC)
        .minus(properties.transaction().currentStateMaxAge());
    List<PlayerStatus> statuses = jdbcTemplate.query("""
        with player_identity as (
          select p.*,
                 case
                   when upper(p.platform) = 'EOS' then 'EOS:' || replace(p.user_id, 'EOS_', '')
                   when p.user_id like 'EOS_%' then 'EOS:' || replace(p.user_id, 'EOS_', '')
                   when upper(coalesce(p.native_platform, '')) = 'EOS' then 'EOS:' || replace(p.native_user_id, 'EOS_', '')
                   when p.native_user_id like 'EOS_%' then 'EOS:' || replace(p.native_user_id, 'EOS_', '')
                 end as eos_key,
                 case
                   when upper(p.platform) = 'STEAM' then 'Steam:' || replace(p.user_id, 'Steam_', '')
                   when p.user_id like 'Steam_%' then 'Steam:' || replace(p.user_id, 'Steam_', '')
                   when upper(coalesce(p.native_platform, '')) = 'STEAM' then 'Steam:' || replace(p.native_user_id, 'Steam_', '')
                   when p.native_user_id like 'Steam_%' then 'Steam:' || replace(p.native_user_id, 'Steam_', '')
                 end as steam_key
          from m_player p
          where p.id = ?
        ),
        latest_snapshot as (
          select *
          from (
            select s.*,
                   row_number() over (partition by player_id order by captured_at desc, id desc) as snapshot_rank
            from t_player_state_snapshot s
            where s.player_id = ?
          ) ranked_snapshot
          where snapshot_rank = 1
        ),
        latest_current_state as (
          select *
          from (
            select c.*,
                   case
                     when c.cross_platform_id is not null and c.cross_platform_id <> '' then 'EOS:' || replace(c.cross_platform_id, 'EOS_', '')
                     when c.platform_id is not null and c.platform_id <> '' then 'Steam:' || replace(c.platform_id, 'Steam_', '')
                     else 'ENTITY:' || c.player_entity_id
                   end as state_player_key,
                   row_number() over (
                     partition by coalesce(
                       'PLAYER:' || c.player_id,
                       case
                         when c.cross_platform_id is not null and c.cross_platform_id <> '' then 'EOS:' || replace(c.cross_platform_id, 'EOS_', '')
                         when c.platform_id is not null and c.platform_id <> '' then 'Steam:' || replace(c.platform_id, 'Steam_', '')
                         else 'ENTITY:' || c.player_entity_id
                       end
                     )
                     order by c.last_updated desc, c.online desc
                   ) as state_rank
            from t_player_current_state c
          ) ranked_state
          where state_rank = 1
        ),
        latest_position as (
          select *
          from (
            select pp.*,
                   row_number() over (partition by player_id order by occurred_at desc) as position_rank
            from t_player_position_transaction pp
            where pp.player_id = ?
          ) ranked_position
          where position_rank = 1
        ),
        stationary_players as (
          -- Keep the detail view on the same three-minute / 20m rule as the dashboard.
          select lp.player_id
          from latest_position lp
          where exists (
              select 1
              from t_player_position_transaction older
              where older.player_id = lp.player_id
                and older.occurred_at between lp.occurred_at - interval '10' minute
                                              and lp.occurred_at - interval '3' minute
                and ((older.position_x - lp.position_x) * (older.position_x - lp.position_x)
                  + (older.position_z - lp.position_z) * (older.position_z - lp.position_z)) <= 400
            )
            and not exists (
              select 1
              from t_player_position_transaction recent
              where recent.player_id = lp.player_id
                and recent.occurred_at between lp.occurred_at - interval '3' minute
                                               and lp.occurred_at
                and ((recent.position_x - lp.position_x) * (recent.position_x - lp.position_x)
                  + (recent.position_z - lp.position_z) * (recent.position_z - lp.position_z)) > 400
            )
        )
        select p.id as player_id,
               p.player_name,
               s.world_name,
               s.game_name,
               coalesce(c.last_updated, pp.occurred_at, s.last_login) as last_login,
               coalesce(c.position_x, pp.position_x, s.x) as x,
               coalesce(c.position_y, pp.position_y, s.y) as y,
               coalesce(c.position_z, pp.position_z, s.z) as z,
               c.health,
               c.deaths,
               c.level,
               c.ping,
               (
                 select coalesce(sum(d.movement_distance), 0)
                 from t_player_position_transaction d
                 where d.player_id = p.id and d.movement_mode = 'ON_FOOT'
               ) as travel_distance,
               (
                 select coalesce(sum(v.movement_distance), 0)
                 from t_vehicle_position_transaction v
                 where v.attributed_player_id = p.id and v.movement_valid = true
               ) as vehicle_distance,
               (
                 select coalesce(v.vehicle_name, v.vehicle_type)
                 from t_vehicle_current_state v
                 where v.owner_player_id = p.id and v.active = true
                   and c.position_x is not null and c.position_z is not null
                   and ((v.position_x - c.position_x) * (v.position_x - c.position_x)
                     + (v.position_z - c.position_z) * (v.position_z - c.position_z)) <= 64
                 order by v.last_updated desc
                 limit 1
               ) as current_vehicle,
               case
                 when c.online = true and c.last_updated >= ? then true
                 else false
               end as online,
               case when stationary.player_id is not null then true else false end as exploring,
               (
                 select poi.poi_name
                 from m_world_poi poi
                 where coalesce(poi.category, '') <> 'part'
                   and poi.poi_name not like 'part_%'
                 order by ((poi.x - coalesce(c.position_x, pp.position_x, s.x)) * (poi.x - coalesce(c.position_x, pp.position_x, s.x))
                       + (poi.z - coalesce(c.position_z, pp.position_z, s.z)) * (poi.z - coalesce(c.position_z, pp.position_z, s.z)))
                 limit 1
               ) as poi_name,
               (
                 select poi.category
                 from m_world_poi poi
                 where coalesce(poi.category, '') <> 'part'
                   and poi.poi_name not like 'part_%'
                 order by ((poi.x - coalesce(c.position_x, pp.position_x, s.x)) * (poi.x - coalesce(c.position_x, pp.position_x, s.x))
                       + (poi.z - coalesce(c.position_z, pp.position_z, s.z)) * (poi.z - coalesce(c.position_z, pp.position_z, s.z)))
                 limit 1
               ) as poi_category
               , (select status from t_player_status ps where ps.player_id = p.id) as custom_status
        from player_identity p
        left join latest_snapshot s on s.player_id = p.id
        left join latest_current_state c on c.player_id = p.id
            or (c.player_id is null and c.state_player_key in (p.eos_key, p.steam_key, p.player_key))
        left join latest_position pp on pp.player_id = p.id
        left join stationary_players stationary on stationary.player_id = p.id
        """, (rs, rowNum) -> new PlayerStatus(
        rs.getLong("player_id"),
        rs.getString("player_name"),
        rs.getString("world_name"),
        rs.getString("game_name"),
        toDisplayTime(rs.getObject("last_login")),
        coordinate(rs.getObject("x"), rs.getObject("y"), rs.getObject("z")),
        displayPoi(rs.getString("poi_name")),
        rs.getString("poi_category"),
        integer(rs, "health"),
        integer(rs, "deaths"),
        integer(rs, "level"),
        integer(rs, "ping"),
        rs.getBigDecimal("travel_distance"),
        rs.getBigDecimal("vehicle_distance"),
        rs.getString("current_vehicle"),
        booleanValue(rs, "online"),
        booleanValue(rs, "exploring"),
        rs.getString("custom_status")), playerId, playerId, playerId, currentStateFreshAfter);
    if (statuses.isEmpty()) {
      return Optional.empty();
    }
    PlayerStatus status = statuses.getFirst();
    return Optional.of(new PlayerDetailView(
        status,
        playerInsights(playerId, status.playerName()),
        playerTimelineEntries(playerId),
        playerPositionEntries(playerId)));
  }

  private PlayerInsights playerInsights(Long playerId, String playerName) {
    long kills = countForPlayer("t_entity_kill_transaction", playerId);
    Long encounterCount = jdbcTemplate.queryForObject("""
        select count(*) from t_sleeper_transaction
        where player_id = ? and transaction_type = 'SLEEPER_SPAWN'
        """, Long.class, playerId);
    long encounters = encounterCount == null ? 0 : encounterCount;
    Long activeDays = jdbcTemplate.queryForObject("""
        select count(distinct activity_day) from (
          select cast(occurred_at as date) as activity_day from t_player_join_transaction where player_id = ?
          union select cast(occurred_at as date) from t_player_position_transaction where player_id = ?
          union select cast(occurred_at as date) from t_entity_kill_transaction where player_id = ?
        ) days
        """, Long.class, playerId, playerId, playerId);
    String favoriteVehicle = jdbcTemplate.query("""
        select coalesce(v.vehicle_name, v.vehicle_type) as vehicle_name
        from t_vehicle_position_transaction v
        where v.attributed_player_id = ? and v.movement_valid = true
        group by coalesce(v.vehicle_name, v.vehicle_type)
        order by sum(v.movement_distance) desc limit 1
        """, rs -> rs.next() ? rs.getString("vehicle_name") : "未記録", playerId);
    List<PlayerDailyActivity> daily = jdbcTemplate.query("""
        select activity_day, sum(kills) as kills, sum(distance) as distance from (
          select cast(occurred_at as date) as activity_day, count(*) as kills, cast(0 as numeric) as distance
          from t_entity_kill_transaction where player_id = ? group by cast(occurred_at as date)
          union all
          select cast(occurred_at as date), 0, coalesce(sum(movement_distance), 0)
          from t_player_position_transaction where player_id = ? and movement_mode = 'ON_FOOT'
          group by cast(occurred_at as date)
          union all
          select cast(v.occurred_at as date), 0, coalesce(sum(v.movement_distance), 0)
          from t_vehicle_position_transaction v
          where v.attributed_player_id = ? and v.movement_valid = true
          group by cast(v.occurred_at as date)
        ) activity group by activity_day order by activity_day desc limit 14
        """, (rs, rowNum) -> new PlayerDailyActivity(
        rs.getObject("activity_day").toString(), rs.getLong("kills"),
        rs.getBigDecimal("distance"), 0), playerId, playerId, playerId);
    daily = new ArrayList<>(daily);
    java.util.Collections.reverse(daily);
    long maxScore = daily.stream().mapToLong(day -> day.kills() * 100
        + day.distance().divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValue()).max().orElse(1);
    List<PlayerDailyActivity> chart = daily.stream().map(day -> {
      long score = day.kills() * 100
          + day.distance().divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValue();
      return new PlayerDailyActivity(
          day.day(), day.kills(), day.distance(), Math.max(4, score * 100 / Math.max(1, maxScore)));
    }).toList();
    return new PlayerInsights(
        kills, encounters, activeDays == null ? 0 : activeDays,
        playMinutesByPlayer().getOrDefault(playerName, 0L), favoriteVehicle, chart);
  }

  private long countForPlayer(String table, Long playerId) {
    Long count = jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where player_id = ?", Long.class, playerId);
    return count == null ? 0 : count;
  }

  private List<TravelEntry> travelEntries() {
    List<TravelEntry> entries = jdbcTemplate.query("""
        with timeline_entries as (
          select occurred_at,
                 'JOIN' as kind,
                 player_name,
                 'ログインした' as action_text,
                 null as detail_text,
                 null as translation_key,
                 position_x as x,
                 position_y as y,
                 position_z as z
          from (select * from t_player_join_transaction order by occurred_at desc limit 120) j
          union all
          select occurred_at,
                 'LEAVE' as kind,
                 player_name,
                 'ログアウトした' as action_text,
                 null as detail_text,
                 null as translation_key,
                 null as x,
                 null as y,
                 null as z
          from (select * from t_player_leave_transaction order by occurred_at desc limit 120) recent_leave
          union all
          select k.occurred_at,
                 'KILL' as kind,
                 k.player_name,
                 '討伐した' as action_text,
                 k.target_entity_type as detail_text,
                 k.target_entity_type as translation_key,
                 k.player_position_x as x,
                 k.player_position_y as y,
                 k.player_position_z as z
          from (select * from t_entity_kill_transaction
                where lower(player_name) not like 'zombie%'
                  and lower(player_name) not like 'animal%'
                order by occurred_at desc limit 120) k
          union all
          select occurred_at,
                 transaction_type as kind,
                 coalesce(s.player_name, (
                   select pp.player_name
                   from t_player_position_transaction pp
                   where pp.occurred_at between s.occurred_at - interval '120' second
                                             and s.occurred_at + interval '120' second
                     and ((pp.position_x - s.position_x) * (pp.position_x - s.position_x)
                       + (pp.position_z - s.position_z) * (pp.position_z - s.position_z)) <= 22500
                   order by ((pp.position_x - s.position_x) * (pp.position_x - s.position_x)
                         + (pp.position_z - s.position_z) * (pp.position_z - s.position_z)),
                            pp.occurred_at desc
                   limit 1
                 )) as player_name,
                 case when transaction_type = 'SLEEPER_SPAWN'
                   then '眠っていた敵を起こした'
                   else '眠っていた敵が再配置された'
                 end as action_text,
                 s.entity_class as detail_text,
                 s.entity_class as translation_key,
                 coalesce(player_position_x, position_x) as x,
                 coalesce(player_position_y, position_y) as y,
                 coalesce(player_position_z, position_z) as z
          from (select * from t_sleeper_transaction
                where transaction_type <> 'SLEEPER_RESTORE'
                order by occurred_at desc limit 120) s
          union all
          select v.occurred_at,
                 'VEHICLE_MOVE' as kind,
                 p.player_name,
                 '移動した' as action_text,
                 coalesce(v.vehicle_name, v.vehicle_type) || '|' ||
                   cast(round(v.movement_distance, 1) as varchar) as detail_text,
                 null as translation_key,
                 v.position_x as x,
                 v.position_y as y,
                 v.position_z as z
          from (select * from t_vehicle_position_transaction
                where movement_valid = true and attributed_player_id is not null
                  and movement_distance >= 1 order by occurred_at desc limit 120) v
          join m_player p on p.id = v.attributed_player_id
          union all
          select occurred_at,
                 'XP' as kind,
                 player_name,
                 'レベル経験値を獲得した' as action_text,
                 '合計 ' || xp_total as detail_text,
                 null as translation_key,
                 null as x,
                 null as y,
                 null as z
          from (select * from t_level_xp_summary_transaction order by occurred_at desc limit 120) recent_xp
          union all
          select observed_at as occurred_at,
                 'DAY_START' as kind,
                 'WATCHPOINT' as player_name,
                 '新しい一日を観測した' as action_text,
                 'DAY ' || game_day as detail_text,
                 null as translation_key,
                 null as x,
                 null as y,
                 null as z
          from (
            select observed_at, game_day,
                   row_number() over (partition by game_day order by observed_at) as day_rank
            from t_world_time_observation
          ) world_day
          where day_rank = 1
          union all
          select w.occurred_at,
                 w.event_type as kind,
                 w.actor_player_name as player_name,
                 case w.event_type
                   when 'AIR_DROP' then '補給物資が投下された'
                   when 'WANDERING_HORDE' then '徘徊ホードが発生した'
                   when 'SCOUT_HORDE' then 'スクリーマーの気配がした'
                   when 'SCREAMER_SPAWN' then 'スクリーマーが出現した'
                   when 'BLOOD_MOON' then 'ブラッドムーン予定が更新された'
                   when 'PLAYER_DEATH' then '力尽きた'
                   else 'イベントが発生した'
                 end as action_text,
                 w.detail_text,
                 null as translation_key,
                 w.position_x as x,
                 w.position_y as y,
                 w.position_z as z
          from (select * from t_world_event_transaction
                where event_type <> 'BLOOD_MOON'
                order by occurred_at desc limit 120) w
          union all
          select v.occurred_at,
                 v.event_type as kind,
                 p.player_name,
                 case
                   when v.event_type = 'VEHICLE_REMOVED'
                     and lower(coalesce(v.removal_reason, '')) = 'unloaded'
                     then '乗り物の追跡範囲から外れた'
                   when v.event_type = 'VEHICLE_REMOVED' then '乗り物が破壊された'
                   when v.event_type = 'VEHICLE_LOADED' then '乗り物を確認した'
                   when v.event_type = 'VEHICLE_POST_INIT' then '乗り物が生成された'
                   else '乗り物を確認した'
                 end as action_text,
                 coalesce(v.vehicle_name, v.vehicle_type) ||
                   case when v.removal_reason is not null then ' / ' || v.removal_reason else '' end as detail_text,
                 null as translation_key,
                 v.position_x as x,
                 v.position_y as y,
                 v.position_z as z
          from (select * from t_vehicle_position_transaction
                where event_type in ('VEHICLE_REMOVED', 'VEHICLE_LOADED', 'VEHICLE_POST_INIT')
                  and (movement_distance < 1 or event_type = 'VEHICLE_REMOVED')
                order by occurred_at desc limit 120) v
          left join m_player p on p.id = v.owner_player_id
              or (v.owner_player_id is null
                  and v.owner_cross_platform_id is not null
                  and p.player_key = 'EOS:' || replace(v.owner_cross_platform_id, 'EOS_', ''))
        ), recent_entries as (
          select * from timeline_entries order by occurred_at desc limit 120
        )
        select e.occurred_at, e.kind, e.player_name, e.action_text,
               coalesce((select tr.display_text from m_japanese_translation tr
                         where tr.localization_key = e.translation_key limit 1),
                        e.detail_text) as detail_text,
               (select poi.poi_name from m_world_poi poi
                where e.x is not null
                  and coalesce(poi.category, '') <> 'part'
                  and poi.poi_name not like 'part_%'
                order by ((poi.x - e.x) * (poi.x - e.x) + (poi.z - e.z) * (poi.z - e.z))
                limit 1) as poi_name,
               e.x, e.y, e.z
        from recent_entries e
        order by e.occurred_at desc
        """, (rs, rowNum) -> new TravelEntry(
        toDisplayTime(rs.getObject("occurred_at")),
        rs.getString("kind"),
        eventTone(rs.getString("kind")),
        displayPlayer(rs.getString("player_name")),
        rs.getString("action_text"),
        rs.getString("detail_text"),
        eventMessageFormatter.format(
            rs.getString("kind"),
            displayPlayer(rs.getString("player_name")),
            rs.getString("action_text"),
            rs.getString("detail_text"),
            displayEventPoi(rs.getString("poi_name"))),
        displayEventPoi(rs.getString("poi_name")),
        coordinate(rs.getObject("x"), rs.getObject("y"), rs.getObject("z"))));
    return condenseTimeline(aggregateSleeperEncounters(entries), 80);
  }

  private List<TravelEntry> aggregateSleeperEncounters(List<TravelEntry> entries) {
    Map<String, List<TravelEntry>> groups = new LinkedHashMap<>();
    for (TravelEntry entry : entries) {
      String key = "SLEEPER_SPAWN".equals(entry.kind())
          ? entry.kind() + "|" + entry.occurredAt() + "|" + entry.actor() + "|" + entry.poiName()
          : "EVENT|" + groups.size();
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
    }
    List<TravelEntry> aggregated = new ArrayList<>();
    for (List<TravelEntry> group : groups.values()) {
      TravelEntry first = group.getFirst();
      if (!"SLEEPER_SPAWN".equals(first.kind()) || group.size() == 1) {
        aggregated.add(first);
        continue;
      }
      List<String> enemies = group.stream()
          .map(TravelEntry::detailText)
          .filter(name -> name != null && !name.isBlank())
          .distinct()
          .limit(3)
          .toList();
      String enemySummary = group.size() >= 4
          ? "大量の敵（" + group.size() + "体）"
          : String.join("、", enemies) + "（" + group.size() + "体）";
      String place = first.poiName() == null || first.poiName().isBlank()
          ? "探索先"
          : first.poiName();
      String message = first.actor() + "が" + place + "で" + enemySummary
          + "を目覚めさせた！\n静かな探索は、ここで終了。";
      aggregated.add(new TravelEntry(
          first.occurredAt(), first.kind(), first.tone(), first.actor(),
          first.actionText(), enemySummary, message, first.poiName(), first.coordinate()));
    }
    return List.copyOf(aggregated);
  }

  private List<TravelEntry> condenseTimeline(List<TravelEntry> entries, int limit) {
    List<TravelEntry> condensed = new ArrayList<>();
    Set<String> playerMinuteBuckets = new HashSet<>();
    int regularEventCount = 0;
    for (TravelEntry entry : entries) {
      boolean alwaysVisible = TimelineEventPolicy.isAlwaysVisible(entry.kind());
      if (!alwaysVisible && regularEventCount >= limit) {
        continue;
      }
      boolean playerEvent = entry.actor() != null && !entry.actor().isBlank() && !"誰か".equals(entry.actor());
      String minute = entry.occurredAt() == null || entry.occurredAt().length() < 16
          ? entry.occurredAt()
          : entry.occurredAt().substring(0, 16);
      if (!alwaysVisible
          && playerEvent
          && !playerMinuteBuckets.add(entry.actor() + "|" + minute)) {
        continue;
      }
      condensed.add(entry);
      if (!alwaysVisible) {
        regularEventCount++;
      }
    }
    return List.copyOf(condensed);
  }

  private String eventTone(String kind) {
    if (kind == null) {
      return "neutral";
    }
    return switch (kind) {
      case "JOIN" -> "login";
      case "LEAVE" -> "logout";
      case "KILL", "PLAYER_DEATH" -> "combat";
      case "VEHICLE_MOVE", "VEHICLE_LOADED", "VEHICLE_POST_INIT", "VEHICLE_REMOVED" -> "movement";
      case "SLEEPER_SPAWN", "WANDERING_HORDE", "SCOUT_HORDE", "SCREAMER_SPAWN" -> "warning";
      case "XP", "DAY_START" -> "exploration";
      default -> "neutral";
    };
  }

  private List<VehicleStatus> vehicleStatuses() {
    return jdbcTemplate.query("""
        select min(v.vehicle_entity_id) as representative_id,
               coalesce(v.vehicle_name, v.vehicle_type) as vehicle_name,
               p.player_name as driver_name,
               count(distinct v.vehicle_entity_id) as vehicle_count,
               sum(v.movement_distance) as total_distance,
               max(case when current_state.active = true then 1 else 0 end) as active_count,
               max(v.occurred_at) as last_updated
        from t_vehicle_position_transaction v
        join m_player p on p.id = v.attributed_player_id
        left join t_vehicle_current_state current_state
          on current_state.vehicle_entity_id = v.vehicle_entity_id
        where v.movement_valid = true and v.movement_distance > 0
        group by p.id, p.player_name, coalesce(v.vehicle_name, v.vehicle_type)
        order by total_distance desc, driver_name, vehicle_name
        """, (rs, rowNum) -> new VehicleStatus(
        rs.getInt("representative_id"),
        rs.getString("vehicle_name"),
        rs.getString("driver_name"),
        rs.getInt("vehicle_count"),
        rs.getBigDecimal("total_distance"),
        rs.getInt("active_count") > 0,
        toDisplayTime(rs.getObject("last_updated"))));
  }

  private List<TravelEntry> playerTimelineEntries(Long playerId) {
    return jdbcTemplate.query("""
        select *
        from (
          select occurred_at, 'JOIN' as kind, player_name, 'ログインした' as action_text,
                 null as detail_text, position_x as x, position_y as y, position_z as z
          from t_player_join_transaction
          where player_id = ?
          union all
          select occurred_at, 'LEAVE' as kind, player_name, 'ログアウトした' as action_text,
                 null as detail_text, null as x, null as y, null as z
          from t_player_leave_transaction
          where player_id = ?
          union all
          select occurred_at, 'KILL' as kind, player_name, '討伐した' as action_text,
                 target_entity_type as detail_text, player_position_x as x, player_position_y as y, player_position_z as z
          from t_entity_kill_transaction
          where player_id = ?
          union all
          select occurred_at, transaction_type as kind, player_name, '眠っていた敵を起こした' as action_text,
                 entity_class as detail_text, coalesce(player_position_x, position_x) as x,
                 coalesce(player_position_y, position_y) as y, coalesce(player_position_z, position_z) as z
          from t_sleeper_transaction
          where player_id = ?
            and transaction_type <> 'SLEEPER_RESTORE'
          union all
          select occurred_at, event_type as kind, actor_player_name as player_name, 'イベントが発生した' as action_text,
                 detail_text, position_x as x, position_y as y, position_z as z
          from t_world_event_transaction
          where player_id = ?
        ) entries
        order by occurred_at desc
        limit 40
        """, (rs, rowNum) -> new TravelEntry(
        toDisplayTime(rs.getObject("occurred_at")),
        rs.getString("kind"),
        eventTone(rs.getString("kind")),
        displayPlayer(rs.getString("player_name")),
        rs.getString("action_text"),
        rs.getString("detail_text"),
        eventMessageFormatter.format(
            rs.getString("kind"),
            displayPlayer(rs.getString("player_name")),
            rs.getString("action_text"),
            rs.getString("detail_text"),
            ""),
        "",
        coordinate(rs.getObject("x"), rs.getObject("y"), rs.getObject("z"))), playerId, playerId, playerId, playerId, playerId);
  }

  private List<PositionEntry> playerPositionEntries(Long playerId) {
    return jdbcTemplate.query("""
        select occurred_at, position_source_type, inference_method, position_x, position_y, position_z
        from t_player_position_transaction
        where player_id = ?
        order by occurred_at desc
        limit 80
        """, (rs, rowNum) -> new PositionEntry(
        toDisplayTime(rs.getObject("occurred_at")),
        rs.getString("position_source_type"),
        rs.getString("inference_method"),
        coordinate(rs.getObject("position_x"), rs.getObject("position_y"), rs.getObject("position_z"))), playerId);
  }

  private List<KillLeader> killLeaders() {
    return jdbcTemplate.query("""
        select player_name, count(*) as kills
        from t_entity_kill_transaction
        where lower(player_name) not like 'zombie%'
          and lower(player_name) not like 'animal%'
        group by player_name
        order by kills desc, player_name
        limit 8
        """, (rs, rowNum) -> new KillLeader(
        rs.getString("player_name"),
        rs.getLong("kills")));
  }

  public KillDetailView killDetail() {
    List<KillEvent> recent = jdbcTemplate.query("""
        select k.occurred_at, k.player_name,
               coalesce((select tr.display_text from m_japanese_translation tr
                         where tr.localization_key = k.target_entity_type limit 1),
                        k.target_entity_type) as target_name,
               k.player_position_x, k.player_position_y, k.player_position_z
        from t_entity_kill_transaction k
        where lower(k.player_name) not like 'zombie%'
          and lower(k.player_name) not like 'animal%'
        order by k.occurred_at desc
        limit 100
        """, (rs, rowNum) -> new KillEvent(
        toDisplayTime(rs.getObject("occurred_at")),
        displayPlayer(rs.getString("player_name")),
        rs.getString("target_name"),
        coordinate(rs.getObject("player_position_x"), rs.getObject("player_position_y"),
            rs.getObject("player_position_z"))));
    List<SessionEvent> sessionEvents = sessionEvents();
    return new KillDetailView(
        adventureRankings(sessionEvents),
        recent,
        dailyActivity(),
        dailyKillActivity(),
        defeatedEnemyRankings(),
        growthTrend(),
        adventureScoreTrend(sessionEvents));
  }

  private List<DefeatedEnemyRanking> defeatedEnemyRankings() {
    return jdbcTemplate.query("""
        select k.target_entity_type,
               coalesce((select tr.display_text from m_japanese_translation tr
                         where tr.localization_key = k.target_entity_type limit 1),
                        k.target_entity_type) as target_name,
               count(*) as defeated_count,
               count(distinct k.player_name) as hunter_count,
               max(k.occurred_at) as last_defeated_at
        from t_entity_kill_transaction k
        where lower(k.player_name) not like 'zombie%'
          and lower(k.player_name) not like 'animal%'
        group by k.target_entity_type
        order by defeated_count desc, target_name
        limit 12
        """, (rs, rowNum) -> new DefeatedEnemyRanking(
        rs.getString("target_name"),
        rs.getLong("defeated_count"),
        rs.getLong("hunter_count"),
        toDisplayTime(rs.getObject("last_defeated_at"))));
  }

  private List<DailyActivity> dailyKillActivity() {
    return dailyCounts("t_entity_kill_transaction");
  }

  private GrowthTrend growthTrend() {
    List<GrowthReport> reports = jdbcTemplate.query("""
        select occurred_at, xp_from_kill, xp_from_loot, xp_from_harvesting
        from t_level_xp_summary_transaction
        order by occurred_at, level_xp_summary_transaction_id
        """, (rs, rowNum) -> new GrowthReport(
        rs.getObject("occurred_at", OffsetDateTime.class),
        rs.getLong("xp_from_kill"),
        rs.getLong("xp_from_loot"),
        rs.getLong("xp_from_harvesting")));
    GrowthAccumulator growth = new GrowthAccumulator();
    reports.forEach(growth::add);
    return growth.toTrend();
  }

  private String growthChartPoints(
      List<GrowthPoint> points, long maxXp,
      java.util.function.ToLongFunction<GrowthPoint> value) {
    if (points.isEmpty()) {
      return "";
    }
    int lastIndex = Math.max(1, points.size() - 1);
    return java.util.stream.IntStream.range(0, points.size())
        .mapToObj(index -> {
          double x = index * 300.0 / lastIndex;
          double y = 76.0 - (value.applyAsLong(points.get(index)) * 68.0 / Math.max(1, maxXp));
          return String.format(java.util.Locale.ROOT, "%.1f,%.1f", x, y);
        })
        .collect(java.util.stream.Collectors.joining(" "));
  }

  private final class GrowthAccumulator {
    private long kills;
    private long loot;
    private long harvest;
    private long total;
    private final List<GrowthPoint> points = new ArrayList<>();

    private void add(GrowthReport report) {
      kills += report.killXp();
      loot += report.lootXp();
      harvest += report.harvestXp();
      total += report.killXp() + report.lootXp() + report.harvestXp();
      points.add(new GrowthPoint(
          report.occurredAt().atZoneSameInstant(DisplayTimeFormatter.JST).toLocalDate().toString(),
          total));
    }

    private GrowthTrend toTrend() {
      List<GrowthPoint> visiblePoints = points.size() <= 30
          ? List.copyOf(points)
          : java.util.stream.IntStream.range(0, 30)
              .map(index -> index * (points.size() - 1) / 29)
              .mapToObj(points::get)
              .toList();
      return new GrowthTrend(
          total, kills, loot, harvest, points.size(),
          growthChartPoints(visiblePoints, total, GrowthPoint::totalXp),
          visiblePoints.isEmpty() ? "" : visiblePoints.getFirst().date(),
          visiblePoints.isEmpty() ? "" : visiblePoints.getLast().date());
    }
  }

  private List<AdventureRanking> adventureRankings() {
    return adventureRankings(sessionEvents());
  }

  private List<AdventureRanking> adventureRankings(List<SessionEvent> sessionEvents) {
    Map<String, Long> kills = new HashMap<>();
    killLeaders().forEach(row -> kills.put(row.playerName(), row.kills()));
    List<PlayerStatus> statuses = playerStatuses();
    Map<String, Long> playMinutes = playMinutesByPlayer(sessionEvents, statuses);
    return statuses.stream()
        .map(player -> new AdventureRanking(
            player.playerId(),
            player.playerName(),
            kills.getOrDefault(player.playerName(), 0L),
            player.travelDistance() == null ? BigDecimal.ZERO : player.travelDistance(),
            player.vehicleDistance() == null ? BigDecimal.ZERO : player.vehicleDistance(),
            playMinutes.getOrDefault(player.playerName(), 0L),
            adventureScore(
                kills.getOrDefault(player.playerName(), 0L),
                player.travelDistance(),
                player.vehicleDistance(),
                playMinutes.getOrDefault(player.playerName(), 0L))))
        .sorted(Comparator.comparingLong(AdventureRanking::score).reversed()
            .thenComparing(AdventureRanking::playerName))
        .toList();
  }

  private long adventureScore(long kills, BigDecimal travel, BigDecimal vehicle, long playMinutes) {
    BigDecimal totalDistance = (travel == null ? BigDecimal.ZERO : travel)
        .add(vehicle == null ? BigDecimal.ZERO : vehicle);
    return kills * 100L + playMinutes + totalDistance.divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValue();
  }

  private Map<String, Long> playMinutesByPlayer() {
    return playMinutesByPlayer(sessionEvents(), playerStatuses());
  }

  private Map<String, Long> playMinutesByPlayer(
      List<SessionEvent> events, List<PlayerStatus> statuses) {
    Map<String, Long> playerIdsByName = new HashMap<>();
    events.stream().filter(event -> event.playerId() != null).forEach(
        event -> playerIdsByName.put(event.playerName(), event.playerId()));
    statuses.forEach(status -> playerIdsByName.put(status.playerName(), status.playerId()));
    Map<String, OffsetDateTime> openSessions = new HashMap<>();
    Map<String, String> playerNames = new HashMap<>();
    Map<String, Long> minutes = new HashMap<>();
    for (SessionEvent event : events) {
      Long playerId = event.playerId() == null
          ? playerIdsByName.get(event.playerName())
          : event.playerId();
      String key = sessionPlayerKey(playerId, event.playerName());
      playerNames.put(key, event.playerName());
      if ("JOIN".equals(event.kind())) {
        // A second JOIN without a LEAVE means the earlier session boundary is unknown.
        // Replace it instead of pairing a future LEAVE with a stale JOIN and inflating play time.
        openSessions.put(key, event.occurredAt());
        continue;
      }
      OffsetDateTime joinedAt = openSessions.remove(key);
      if (joinedAt == null) {
        continue;
      }
      long sessionMinutes = Math.max(0, Duration.between(joinedAt, event.occurredAt()).toMinutes());
      minutes.merge(event.playerName(), sessionMinutes, Long::sum);
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (PlayerStatus status : statuses) {
      if (!status.online()) {
        continue;
      }
      String key = sessionPlayerKey(status.playerId(), status.playerName());
      OffsetDateTime joinedAt = openSessions.remove(key);
      if (joinedAt == null) {
        continue;
      }
      String playerName = playerNames.getOrDefault(key, status.playerName());
      minutes.merge(playerName, Math.max(0, Duration.between(joinedAt, now).toMinutes()), Long::sum);
    }
    return minutes;
  }

  private String sessionPlayerKey(Long playerId, String playerName) {
    return playerId == null ? "NAME:" + playerName : "PLAYER:" + playerId;
  }

  private ScoreChart adventureScoreTrend(List<SessionEvent> sessions) {
    List<ScoreEvent> scoreEvents = jdbcTemplate.query("""
        select occurred_at, player_name, score_delta from (
          select occurred_at, player_name, cast(100 as numeric) as score_delta
          from t_entity_kill_transaction
          union all
          select occurred_at, player_name, movement_distance / 100 as score_delta
          from t_player_position_transaction where movement_distance > 0 and movement_mode = 'ON_FOOT'
          union all
          select v.occurred_at, p.player_name, v.movement_distance / 100 as score_delta
          from t_vehicle_position_transaction v
          join m_player p on p.id = v.attributed_player_id
          where v.movement_valid = true and v.movement_distance > 0
        ) score_events
        where player_name is not null and player_name <> ''
        order by occurred_at
        """, (rs, rowNum) -> new ScoreEvent(
        rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("player_name"),
        rs.getBigDecimal("score_delta")));
    scoreEvents = new ArrayList<>(scoreEvents);
    Map<String, Deque<OffsetDateTime>> openSessions = new HashMap<>();
    for (SessionEvent event : sessions) {
      if ("JOIN".equals(event.kind())) {
        openSessions.computeIfAbsent(event.playerName(), ignored -> new ArrayDeque<>())
            .addLast(event.occurredAt());
        continue;
      }
      Deque<OffsetDateTime> joins = openSessions.get(event.playerName());
      if (joins == null || joins.isEmpty()) {
        continue;
      }
      long minutes = Math.min(720, Math.max(0,
          Duration.between(joins.removeFirst(), event.occurredAt()).toMinutes()));
      scoreEvents.add(new ScoreEvent(
          event.occurredAt(), event.playerName(), BigDecimal.valueOf(minutes)));
    }
    scoreEvents.sort(Comparator.comparing(ScoreEvent::occurredAt));
    if (scoreEvents.isEmpty()) {
      return new ScoreChart(List.of(), "", "");
    }
    Map<String, List<ScorePoint>> pointsByPlayer = new HashMap<>();
    Map<String, BigDecimal> totals = new HashMap<>();
    for (ScoreEvent event : scoreEvents) {
      BigDecimal total = totals.merge(event.playerName(), event.scoreDelta(), BigDecimal::add);
      pointsByPlayer.computeIfAbsent(event.playerName(), ignored -> new ArrayList<>())
          .add(new ScorePoint(event.occurredAt(), total));
    }
    OffsetDateTime first = scoreEvents.getFirst().occurredAt();
    OffsetDateTime last = scoreEvents.getLast().occurredAt();
    long maxScore = Math.max(1, totals.values().stream()
        .mapToLong(BigDecimal::longValue).max().orElse(1));
    List<ScoreSeries> series = totals.entrySet().stream()
        .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
        .limit(6)
        .map(entry -> new ScoreSeries(
            entry.getKey(), entry.getValue().longValue(),
            scoreSeriesPoints(pointsByPlayer.get(entry.getKey()), first, last, maxScore)))
        .toList();
    return new ScoreChart(
        series,
        first.atZoneSameInstant(DisplayTimeFormatter.JST).toLocalDate().toString(),
        last.atZoneSameInstant(DisplayTimeFormatter.JST).toLocalDate().toString());
  }

  private String scoreSeriesPoints(
      List<ScorePoint> points, OffsetDateTime first, OffsetDateTime last, long maxScore) {
    long duration = Math.max(1, Duration.between(first, last).toSeconds());
    return points.stream().map(point -> {
      double x = Duration.between(first, point.occurredAt()).toSeconds() * 300.0 / duration;
      double y = 76.0 - point.score().doubleValue() * 68.0 / maxScore;
      return String.format(java.util.Locale.ROOT, "%.1f,%.1f", x, y);
    }).collect(java.util.stream.Collectors.joining(" "));
  }

  private List<SessionEvent> sessionEvents() {
    return jdbcTemplate.query("""
        select occurred_at, event_kind, player_id, player_name from (
          select occurred_at, 'JOIN' as event_kind, player_id, player_name
          from t_player_join_transaction
          where join_reason in ('JoinMultiplayer', 'EnterMultiplayer')
          union all
          select occurred_at, 'LEAVE' as event_kind, player_id, player_name
          from t_player_leave_transaction
        ) session_events order by occurred_at
        """, (rs, rowNum) -> new SessionEvent(
        rs.getObject("occurred_at", OffsetDateTime.class),
        rs.getString("event_kind"), rs.getObject("player_id", Long.class),
        rs.getString("player_name")));
  }

  private List<DailyActivity> dailyActivity() {
    List<DailyActivityCount> counts = jdbcTemplate.query("""
        select cast(occurred_at as date) as activity_day, count(*) as event_count
        from (
          select occurred_at from t_player_join_transaction
          union all select occurred_at from t_entity_kill_transaction
          union all select occurred_at from t_world_event_transaction where event_type <> 'BLOOD_MOON'
          union all select occurred_at from t_vehicle_position_transaction
        ) activity
        where occurred_at >= ?
        group by cast(occurred_at as date)
        order by activity_day
        """, (rs, rowNum) -> new DailyActivityCount(
        rs.getObject("activity_day").toString(), rs.getLong("event_count")),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(6).withHour(0).withMinute(0).withSecond(0).withNano(0));
    long max = counts.stream().mapToLong(DailyActivityCount::eventCount).max().orElse(1);
    return counts.stream()
        .map(row -> new DailyActivity(row.day(), row.eventCount(), Math.max(4, row.eventCount() * 100 / max)))
        .toList();
  }

  private List<DailyActivity> dailyCounts(String tableName) {
    if (!"t_entity_kill_transaction".equals(tableName)) {
      throw new IllegalArgumentException("Unsupported activity table");
    }
    List<DailyActivityCount> counts = jdbcTemplate.query("""
        select cast(occurred_at as date) as activity_day, count(*) as event_count
        from t_entity_kill_transaction
        where occurred_at >= ?
          and lower(player_name) not like 'zombie%'
          and lower(player_name) not like 'animal%'
        group by cast(occurred_at as date)
        order by activity_day
        """, (rs, rowNum) -> new DailyActivityCount(
        rs.getObject("activity_day").toString(), rs.getLong("event_count")),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(6).withHour(0).withMinute(0).withSecond(0).withNano(0));
    long max = counts.stream().mapToLong(DailyActivityCount::eventCount).max().orElse(1);
    return counts.stream()
        .map(row -> new DailyActivity(row.day(), row.eventCount(), Math.max(4, row.eventCount() * 100 / max)))
        .toList();
  }

  public VehicleDetailView vehicleDetail() {
    List<VehicleStatus> vehicles = vehicleStatuses();
    VehicleSummary summary = jdbcTemplate.queryForObject("""
        select count(distinct vehicle.vehicle_entity_id) as vehicle_count,
               count(distinct case when current_state.active = true then vehicle.vehicle_entity_id end) as active_count,
               count(distinct vehicle.attributed_player_id) as driver_count,
               coalesce(sum(vehicle.movement_distance), 0) as total_distance
        from t_vehicle_position_transaction vehicle
        left join t_vehicle_current_state current_state
          on current_state.vehicle_entity_id = vehicle.vehicle_entity_id
        where vehicle.attributed_player_id is not null and vehicle.movement_valid = true
        """, (rs, rowNum) -> new VehicleSummary(
        rs.getLong("vehicle_count"), rs.getLong("active_count"),
        rs.getLong("driver_count"), rs.getBigDecimal("total_distance")));
    List<VehicleDailyDistance> daily = jdbcTemplate.query("""
        select cast(occurred_at as date) as travel_day,
               coalesce(sum(movement_distance), 0) as distance
        from t_vehicle_position_transaction
        where attributed_player_id is not null and movement_valid = true and movement_distance > 0
        group by cast(occurred_at as date)
        order by travel_day desc limit 14
        """, (rs, rowNum) -> new VehicleDailyDistance(
        rs.getObject("travel_day").toString(), rs.getBigDecimal("distance"), 0));
    daily = new ArrayList<>(daily);
    java.util.Collections.reverse(daily);
    BigDecimal maxDistance = daily.stream().map(VehicleDailyDistance::distance)
        .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
    List<VehicleDailyDistance> chart = daily.stream().map(day -> new VehicleDailyDistance(
        day.day(), day.distance(), Math.max(4,
        day.distance().multiply(BigDecimal.valueOf(100)).divide(
            maxDistance.max(BigDecimal.ONE), 0, RoundingMode.HALF_UP).longValue()))).toList();
    List<VehicleTypeRanking> types = jdbcTemplate.query("""
        select coalesce(vehicle_name, vehicle_type) as vehicle_name,
               count(distinct vehicle_entity_id) as vehicle_count,
               coalesce(sum(movement_distance), 0) as total_distance
        from t_vehicle_position_transaction
        where attributed_player_id is not null and movement_valid = true
        group by coalesce(vehicle_name, vehicle_type)
        order by total_distance desc
        """, (rs, rowNum) -> new VehicleTypeRanking(
        rs.getString("vehicle_name"), rs.getLong("vehicle_count"),
        rs.getBigDecimal("total_distance")));
    return new VehicleDetailView(vehicles, summary, chart, types);
  }

  public ExplorationDetailView explorationDetail() {
    List<PoiExploration> pois = jdbcTemplate.query("""
        select poi.id, poi.poi_name, poi.category, poi.x, poi.y, poi.z,
               case when exists (
                 select 1 from t_player_position_transaction pos
                 where ((pos.position_x - poi.x) * (pos.position_x - poi.x)
                      + (pos.position_z - poi.z) * (pos.position_z - poi.z)) <= 6400
               ) then true else false end as explored,
               (select max(pos.occurred_at) from t_player_position_transaction pos
                where ((pos.position_x - poi.x) * (pos.position_x - poi.x)
                     + (pos.position_z - poi.z) * (pos.position_z - poi.z)) <= 6400) as visited_at,
               (select pos.player_name from t_player_position_transaction pos
                where ((pos.position_x - poi.x) * (pos.position_x - poi.x)
                     + (pos.position_z - poi.z) * (pos.position_z - poi.z)) <= 6400
                order by pos.occurred_at desc limit 1) as visitor_name
        from m_world_poi poi
        where coalesce(poi.category, '') <> 'part' and poi.poi_name not like 'part_%'
        order by explored desc, visited_at desc nulls last, poi.poi_name
        """, (rs, rowNum) -> new PoiExploration(
        rs.getLong("id"),
        displayPoi(rs.getString("poi_name")),
        poiNameService.displayCategory(rs.getString("category")),
        coordinate(rs.getObject("x"), rs.getObject("y"), rs.getObject("z")),
        booleanValue(rs, "explored"),
        toDisplayTime(rs.getObject("visited_at")),
        rs.getString("visitor_name")));
    long explored = pois.stream().filter(poi -> Boolean.TRUE.equals(poi.explored())).count();
    long percentage = pois.isEmpty() ? 0 : explored * 100 / pois.size();
    List<PoiExploration> discoveredPois = pois.stream()
        .filter(poi -> Boolean.TRUE.equals(poi.explored()))
        .toList();
    return new ExplorationDetailView(
        pois.size(), explored, pois.size() - explored, percentage, discoveredPois);
  }

  private ServerState latestServerState() {
    List<ServerState> states = jdbcTemplate.query("""
        select occurred_at, fps, player_count, zombie_count, entity_count, rss_mb
        from t_server_metric
        order by occurred_at desc
        limit 1
        """, (rs, rowNum) -> new ServerState(
        toDisplayTime(rs.getObject("occurred_at")),
        rs.getBigDecimal("fps"),
        integer(rs, "player_count"),
        integer(rs, "zombie_count"),
        integer(rs, "entity_count"),
        rs.getBigDecimal("rss_mb")));
    if (states.isEmpty()) {
      return new ServerState("", null, null, null, null, null);
    }
    return states.getFirst();
  }

  private ServerState withOnlinePlayerCount(ServerState state) {
    OffsetDateTime freshAfter = OffsetDateTime.now(ZoneOffset.UTC)
        .minus(properties.transaction().currentStateMaxAge());
    Integer onlinePlayers = jdbcTemplate.queryForObject("""
        select count(distinct coalesce(
          'PLAYER:' || player_id,
          'EOS:' || replace(cross_platform_id, 'EOS_', ''),
          'STEAM:' || replace(platform_id, 'Steam_', ''),
          'ENTITY:' || player_entity_id
        ))
        from t_player_current_state
        where online = true and last_updated >= ?
        """, Integer.class, freshAfter);
    return new ServerState(state.occurredAt(), state.fps(), onlinePlayers, state.zombieCount(),
        state.entityCount(), state.rssMb());
  }

  public ServerDetailView serverDetail() {
    List<PlayerStatus> players = playerStatuses();
    ServerState current = withOnlinePlayerCount(latestServerState());
    List<ServerMetricObservation> observations = jdbcTemplate.query("""
        select occurred_at, fps, zombie_count, entity_count, rss_mb
        from t_server_metric
        order by occurred_at desc
        limit 120
        """, (rs, rowNum) -> new ServerMetricObservation(
        rs.getObject("occurred_at", OffsetDateTime.class),
        rs.getBigDecimal("fps"),
        integer(rs, "zombie_count"),
        integer(rs, "entity_count"),
        rs.getBigDecimal("rss_mb")));
    List<ServerMetricPoint> history = observations.stream().map(row -> new ServerMetricPoint(
        displayTimeFormatter.format(row.occurredAt()), row.fps(), row.zombieCount(),
        row.entityCount(), row.rssMb())).toList();
    return new ServerDetailView(current, players, history, serverHealth(observations));
  }

  private ServerHealth serverHealth(List<ServerMetricObservation> newestFirst) {
    if (newestFirst.isEmpty()) {
      return new ServerHealth(
          "unknown", "観測待ち", 0, null, null, null, "", "", "", "", List.of());
    }
    List<ServerMetricObservation> chronological = new ArrayList<>(newestFirst);
    java.util.Collections.reverse(chronological);
    List<BigDecimal> fpsValues = chronological.stream().map(ServerMetricObservation::fps)
        .filter(java.util.Objects::nonNull).toList();
    BigDecimal averageFps = fpsValues.isEmpty() ? null : fpsValues.stream()
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(fpsValues.size()), 1, RoundingMode.HALF_UP);
    BigDecimal minimumFps = fpsValues.stream().min(BigDecimal::compareTo).orElse(null);
    BigDecimal maximumRss = chronological.stream().map(ServerMetricObservation::rssMb)
        .filter(java.util.Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
    long stableSamples = fpsValues.stream()
        .filter(fps -> fps.compareTo(BigDecimal.valueOf(18)) >= 0).count();
    long stability = fpsValues.isEmpty() ? 0 : stableSamples * 100 / fpsValues.size();
    List<ServerIncident> incidents = new ArrayList<>();
    ServerMetricObservation previous = null;
    for (ServerMetricObservation point : chronological) {
      List<String> reasons = new ArrayList<>();
      if (point.fps() != null && point.fps().compareTo(BigDecimal.valueOf(15)) < 0) {
        reasons.add("FPS低下 " + point.fps());
      }
      if (previous != null && point.rssMb() != null && previous.rssMb() != null
          && point.rssMb().subtract(previous.rssMb()).compareTo(BigDecimal.valueOf(256)) >= 0) {
        reasons.add("メモリ急増 +" + point.rssMb().subtract(previous.rssMb()).setScale(0, RoundingMode.HALF_UP) + "MB");
      }
      if (!reasons.isEmpty()) {
        incidents.add(new ServerIncident(
            displayTimeFormatter.format(point.occurredAt()), String.join(" / ", reasons)));
      }
      previous = point;
    }
    if (incidents.size() > 8) {
      incidents = incidents.subList(incidents.size() - 8, incidents.size());
    }
    String level = stability >= 90 && incidents.isEmpty() ? "stable"
        : stability >= 70 ? "watch" : "unstable";
    String label = switch (level) {
      case "stable" -> "安定稼働";
      case "watch" -> "一部変動あり";
      default -> "不安定区間あり";
    };
    BigDecimal fpsMax = fpsValues.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
    List<BigDecimal> rssValues = chronological.stream().map(ServerMetricObservation::rssMb)
        .filter(java.util.Objects::nonNull).toList();
    BigDecimal rssMax = rssValues.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
    return new ServerHealth(
        level, label, stability, averageFps, minimumFps, maximumRss,
        metricChartPoints(chronological, fpsMax, ServerMetricObservation::fps),
        metricChartPoints(chronological, rssMax, ServerMetricObservation::rssMb),
        displayTimeFormatter.format(chronological.getFirst().occurredAt()),
        displayTimeFormatter.format(chronological.getLast().occurredAt()),
        List.copyOf(incidents));
  }

  private String metricChartPoints(
      List<ServerMetricObservation> rows, BigDecimal max,
      java.util.function.Function<ServerMetricObservation, BigDecimal> value) {
    int lastIndex = Math.max(1, rows.size() - 1);
    return java.util.stream.IntStream.range(0, rows.size()).mapToObj(index -> {
      BigDecimal metric = value.apply(rows.get(index));
      double x = index * 300.0 / lastIndex;
      double y = metric == null ? 76 : 76 - metric.doubleValue() * 68 / max.max(BigDecimal.ONE).doubleValue();
      return String.format(java.util.Locale.ROOT, "%.1f,%.1f", x, y);
    }).collect(java.util.stream.Collectors.joining(" "));
  }

  private BloodMoonStatus latestBloodMoon(WorldTimeStatus worldTime) {
    List<BloodMoonObservation> rows = jdbcTemplate.query("""
        select occurred_at, detail_text
        from t_world_event_transaction
        where event_type = 'BLOOD_MOON'
        order by occurred_at desc
        limit 1
        """, (rs, rowNum) -> new BloodMoonObservation(
        toDisplayTime(rs.getObject("occurred_at")), rs.getString("detail_text")));
    if (rows.isEmpty()) {
      return new BloodMoonStatus("", "予定情報なし", "血月情報を待機中", "unknown");
    }
    BloodMoonObservation observation = rows.getFirst();
    Matcher matcher = BLOOD_MOON_DAY.matcher(observation.detailText());
    if (worldTime.day() == null || !matcher.find()) {
      return new BloodMoonStatus(
          observation.occurredAt(), observation.detailText(), "現在DAYを観測中", "unknown");
    }
    int remainingDays = Integer.parseInt(matcher.group(1)) - worldTime.day();
    String countdown = switch (remainingDays) {
      case 0 -> "本日";
      case 1 -> "明日";
      default -> remainingDays > 1 ? "あと" + remainingDays + "日" : "予定更新待ち";
    };
    String alertLevel = remainingDays == 0 ? "today" : remainingDays == 1 ? "tomorrow" : "normal";
    return new BloodMoonStatus(
        observation.occurredAt(), observation.detailText(), countdown, alertLevel);
  }

  private record BloodMoonObservation(String occurredAt, String detailText) {
  }

  private WorldTimeStatus latestWorldTime() {
    List<WorldTimeStatus> rows = jdbcTemplate.query("""
        select observed_at, game_day, game_hour, game_minute
        from t_world_time_observation
        order by observed_at desc
        limit 1
        """, (rs, rowNum) -> new WorldTimeStatus(
        toDisplayTime(rs.getObject("observed_at")),
        rs.getInt("game_day"),
        String.format("%02d:%02d", rs.getInt("game_hour"), rs.getInt("game_minute"))));
    return rows.isEmpty() ? new WorldTimeStatus("", null, "--:--") : rows.getFirst();
  }

  private AiComment dailyDiary() {
    return aiCommentService.latestDiary()
        .map(comment -> new AiComment(
            comment.title(), DiaryViewService.excerpt(comment.body(), 150), comment.diaryDate(),
            displayTimeFormatter.format(comment.publishedAt()), comment.sourceType()))
        .orElse(null);
  }

  private Integer integer(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private Boolean booleanValue(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    boolean value = rs.getBoolean(column);
    return rs.wasNull() ? null : value;
  }

  private String coordinate(Object x, Object y, Object z) {
    if (x == null || y == null || z == null) {
      return "";
    }
    return x + ", " + y + ", " + z;
  }

  private String displayPlayer(String playerName) {
    return playerName == null || playerName.isBlank() ? "誰か" : playerName;
  }

  private String displayPoi(String poiName) {
    return poiNameService.displayName(poiName);
  }

  private String displayEventPoi(String poiName) {
    if (poiName == null || poiName.isBlank()) {
      return "";
    }
    return poiNameService.displayName(poiName);
  }

  private String toDisplayTime(Object value) {
    return displayTimeFormatter.format(value);
  }

  public record DashboardView(
      List<PlayerStatus> playerStatuses,
      List<TravelEntry> travelEntries,
      List<VehicleStatus> vehicleStatuses,
      ServerState serverState,
      WorldTimeStatus worldTime,
      BloodMoonStatus bloodMoon,
      AiComment aiComment
  ) {
  }

  public record AiComment(
      String title, String body, LocalDate diaryDate, String publishedAt, String sourceType) {
  }

  public record WorldTimeStatus(String observedAt, Integer day, String time) {
  }

  public record PlayerStatus(
      Long playerId,
      String playerName,
      String worldName,
      String gameName,
      String lastLogin,
      String coordinate,
      String poiName,
      String poiCategory,
      Integer health,
      Integer deaths,
      Integer level,
      Integer ping,
      BigDecimal travelDistance,
      BigDecimal vehicleDistance,
      String currentVehicle,
      Boolean online,
      Boolean exploring,
      String customStatus
  ) {

    public String statusSummary() {
      if (!Boolean.TRUE.equals(online)) {
        return "オフライン / 最終地点：" + displayPoiName();
      }
      String customLabel = PlayerStatusCatalog.displayLabel(customStatus);
      if (customLabel != null) {
        return customLabel;
      }
      return Boolean.TRUE.equals(exploring)
          ? displayPoiName() + " を探索中"
          : displayPoiName() + " 付近を移動中";
    }

    public String statusLabel() {
      if (!Boolean.TRUE.equals(online)) {
        return "オフライン";
      }
      String customLabel = PlayerStatusCatalog.displayLabel(customStatus);
      if (customLabel != null) {
        return customLabel;
      }
      return Boolean.TRUE.equals(exploring) ? "探索中" : "オンライン";
    }

    private String displayPoiName() {
      return poiName == null || poiName.isBlank() ? "最終地点不明" : poiName;
    }
  }

  public record PlayerDetailView(
      PlayerStatus status,
      PlayerInsights insights,
      List<TravelEntry> timelineEntries,
      List<PositionEntry> positionEntries
  ) {
  }

  public record PlayerInsights(
      long kills, long encounters, long activeDays, long playMinutes,
      String favoriteVehicle, List<PlayerDailyActivity> dailyActivity) {
  }

  public record PlayerDailyActivity(
      String day, long kills, BigDecimal distance, long percentage) {
  }

  public record PositionEntry(
      String occurredAt,
      String sourceType,
      String inferenceMethod,
      String coordinate
  ) {
  }

  public record TravelEntry(
      String occurredAt,
      String kind,
      String tone,
      String actor,
      String actionText,
      String detailText,
      String message,
      String poiName,
      String coordinate
  ) {
  }

  public record VehicleStatus(
      Integer vehicleEntityId,
      String vehicleName,
      String driverName,
      Integer vehicleCount,
      BigDecimal totalDistance,
      Boolean active,
      String lastUpdated
  ) {
  }

  public record KillLeader(String playerName, long kills) {
  }

  public record KillEvent(String occurredAt, String playerName, String targetName, String coordinate) {
  }

  public record KillDetailView(
      List<AdventureRanking> rankings,
      List<KillEvent> recentKills,
      List<DailyActivity> dailyActivity,
      List<DailyActivity> dailyKills,
      List<DefeatedEnemyRanking> defeatedEnemies,
      GrowthTrend growthTrend,
      ScoreChart scoreChart
  ) {
  }

  public record DefeatedEnemyRanking(
      String targetName, long defeatedCount, long hunterCount, String lastDefeatedAt) {
  }

  public record GrowthTrend(
      long totalXp, long killXp, long lootXp, long harvestXp, long reports,
      String totalChartPoints,
      String firstDate, String lastDate) {
  }

  private record GrowthReport(
      OffsetDateTime occurredAt, long killXp, long lootXp, long harvestXp) {
  }

  private record GrowthPoint(String date, long totalXp) {
  }

  public record ScoreChart(List<ScoreSeries> series, String firstDate, String lastDate) {
  }

  public record ScoreSeries(String playerName, long score, String chartPoints) {
  }

  private record ScoreEvent(
      OffsetDateTime occurredAt, String playerName, BigDecimal scoreDelta) {
  }

  private record ScorePoint(OffsetDateTime occurredAt, BigDecimal score) {
  }

  public record AdventureRanking(
      Long playerId,
      String playerName,
      long kills,
      BigDecimal travelDistance,
      BigDecimal vehicleDistance,
      long playMinutes,
      long score
  ) {
  }

  private record SessionEvent(
      OffsetDateTime occurredAt, String kind, Long playerId, String playerName) {
  }

  private record DailyActivityCount(String day, long eventCount) {
  }

  public record DailyActivity(String day, long eventCount, long percentage) {
  }

  public record VehicleDetailView(
      List<VehicleStatus> vehicles, VehicleSummary summary,
      List<VehicleDailyDistance> dailyDistances, List<VehicleTypeRanking> typeRankings) {
  }

  public record VehicleSummary(
      long vehicleCount, long activeCount, long driverCount, BigDecimal totalDistance) {
  }

  public record VehicleDailyDistance(String day, BigDecimal distance, long percentage) {
  }

  public record VehicleTypeRanking(
      String vehicleName, long vehicleCount, BigDecimal totalDistance) {
  }

  public record PoiExploration(
      Long poiId, String poiName, String category, String coordinate, Boolean explored,
      String visitedAt, String visitorName) {
  }

  public record ExplorationDetailView(
      long totalCount, long exploredCount, long unexploredCount, long percentage,
      List<PoiExploration> pois) {
  }

  public record BloodMoonStatus(
      String occurredAt, String detailText, String countdownText, String alertLevel) {
  }

  public record ServerMetricPoint(
      String occurredAt,
      BigDecimal fps,
      Integer zombieCount,
      Integer entityCount,
      BigDecimal rssMb
  ) {
  }

  public record ServerDetailView(
      ServerState current,
      List<PlayerStatus> players,
      List<ServerMetricPoint> history,
      ServerHealth health
  ) {
  }

  public record ServerHealth(
      String level, String label, long stabilityPercent,
      BigDecimal averageFps, BigDecimal minimumFps, BigDecimal maximumRss,
      String fpsChartPoints, String rssChartPoints, String firstObservedAt,
      String lastObservedAt, List<ServerIncident> incidents) {
  }

  public record ServerIncident(String occurredAt, String reason) {
  }

  private record ServerMetricObservation(
      OffsetDateTime occurredAt, BigDecimal fps, Integer zombieCount,
      Integer entityCount, BigDecimal rssMb) {
  }

  public record ServerState(
      String occurredAt,
      BigDecimal fps,
      Integer playerCount,
      Integer zombieCount,
      Integer entityCount,
      BigDecimal rssMb
  ) {
  }
}
