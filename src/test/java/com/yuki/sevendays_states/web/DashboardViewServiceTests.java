package com.yuki.sevendays_states.web;

import com.yuki.sevendays_states.util.DisplayTimeFormatter;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sevendays_states_dashboard_service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class DashboardViewServiceTests {

  @Autowired
  private DashboardViewService dashboardViewService;

  @Autowired
  private PoiNameService poiNameService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetData() {
    jdbcTemplate.update("delete from t_server_metric");
    jdbcTemplate.update("delete from t_player_join_transaction");
    jdbcTemplate.update("delete from t_player_leave_transaction");
    jdbcTemplate.update("delete from t_level_xp_summary_transaction");
    jdbcTemplate.update("delete from t_player_position_transaction");
    jdbcTemplate.update("delete from t_player_current_state");
    jdbcTemplate.update("delete from t_player_state_snapshot");
    jdbcTemplate.update("delete from t_entity_kill_transaction");
    jdbcTemplate.update("delete from t_sleeper_transaction");
    jdbcTemplate.update("delete from t_vehicle_position_transaction");
    jdbcTemplate.update("delete from t_vehicle_current_state");
    jdbcTemplate.update("delete from t_world_event_transaction");
    jdbcTemplate.update("delete from t_world_time_observation");
    jdbcTemplate.update("delete from t_ai_comment");
    jdbcTemplate.update("delete from t_player_status");
    jdbcTemplate.update("delete from m_japanese_translation");
    jdbcTemplate.update("delete from m_world_poi");
    jdbcTemplate.update("delete from m_player");
  }

  @Test
  void latestManualAiCommentOverridesGeneratedDashboardComment() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into t_ai_comment (diary_date, title, body, published_at, source_type)
        values ('2026-08-02', '荒野通信', '今日は病院探索が進みました。', ?, 'MANUAL_BETA')
        """, now);

    DashboardViewService.AiComment comment = dashboardViewService.dashboard().aiComment();

    assertThat(comment.title()).isEqualTo("荒野通信");
    assertThat(comment.body()).isEqualTo("今日は病院探索が進みました。");
    assertThat(comment.diaryDate()).isEqualTo(LocalDate.of(2026, 8, 2));
  }

  @Test
  void worldTimeShowsLatestObservationAndAddsOnlyOneTimelineEntryPerDay() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into t_world_time_observation
        (observed_at, game_day, game_hour, game_minute, source, source_hash, raw_response)
        values (?, 12, 4, 50, 'telnet:gettime', 'time-1', 'Day 12, 04:50'),
               (?, 12, 6, 15, 'telnet:gettime', 'time-2', 'Day 12, 06:15')
        """, now.minusMinutes(1), now);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.worldTime().day()).isEqualTo(12);
    assertThat(dashboard.worldTime().time()).isEqualTo("06:15");
    assertThat(dashboard.travelEntries())
        .filteredOn(entry -> "DAY_START".equals(entry.kind()))
        .singleElement()
        .satisfies(entry -> assertThat(entry.message()).contains("DAY 12が始まった"));
  }

  @Test
  void dashboardOnlineCountUsesFreshPlayerStateInsteadOfServerMetricPlayerCount() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA', ?)
        """, Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_id, player_name, position_x, position_y, position_z, online, last_updated)
        values (101, 1, 'PlayerA', 0, 0, 0, false, ?)
        """, now.minusSeconds(5));
    jdbcTemplate.update("""
        insert into t_server_metric
        (occurred_at, fps, player_count, zombie_count, source_file, source_log_hash)
        values (?, 20.0, 2, 27, 'log', 'metric-authoritative-count')
        """, now);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.serverState().playerCount()).isZero();
    assertThat(dashboard.serverState().zombieCount()).isEqualTo(27);
  }

  @Test
  void bloodMoonMovesToSidebarAndIsExcludedFromTimeline() {
    jdbcTemplate.update("""
        insert into t_world_event_transaction
        (occurred_at, event_type, detail_text, source_file, source_log_hash, raw_line)
        values (timestamp with time zone '2026-08-02 01:00:00+00:00', 'BLOOD_MOON',
                'Day 28 / 周期 7', 'log', 'blood-sidebar', 'raw')
        """);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.bloodMoon().detailText()).isEqualTo("Day 28 / 周期 7");
    assertThat(dashboard.travelEntries())
        .extracting(DashboardViewService.TravelEntry::kind)
        .doesNotContain("BLOOD_MOON");
  }

  @Test
  void bloodMoonCountdownWarnsWhenBloodMoonIsToday() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into t_world_time_observation
        (observed_at, game_day, game_hour, game_minute, source, source_hash, raw_response)
        values (?, 21, 18, 30, 'telnet:gettime', 'blood-time-today', 'Day 21, 18:30')
        """, now);
    jdbcTemplate.update("""
        insert into t_world_event_transaction
        (occurred_at, event_type, detail_text, source_file, source_log_hash, raw_line)
        values (?, 'BLOOD_MOON', 'Day 21 / 周期 7', 'log', 'blood-today', 'raw')
        """, now.minusMinutes(1));

    DashboardViewService.BloodMoonStatus bloodMoon = dashboardViewService.dashboard().bloodMoon();

    assertThat(bloodMoon.countdownText()).isEqualTo("本日");
    assertThat(bloodMoon.alertLevel()).isEqualTo("today");
  }

  @Test
  void bloodMoonCountdownShowsRemainingGameDays() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into t_world_time_observation
        (observed_at, game_day, game_hour, game_minute, source, source_hash, raw_response)
        values (?, 18, 9, 0, 'telnet:gettime', 'blood-time-future', 'Day 18, 09:00')
        """, now);
    jdbcTemplate.update("""
        insert into t_world_event_transaction
        (occurred_at, event_type, detail_text, source_file, source_log_hash, raw_line)
        values (?, 'BLOOD_MOON', 'Day 21 / 周期 7', 'log', 'blood-future', 'raw')
        """, now.minusMinutes(1));

    DashboardViewService.BloodMoonStatus bloodMoon = dashboardViewService.dashboard().bloodMoon();

    assertThat(bloodMoon.countdownText()).isEqualTo("あと3日");
    assertThat(bloodMoon.alertLevel()).isEqualTo("normal");
  }

  @Test
  void dashboardCondensesPlayerEventsToOnePerMinute() {
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, target_entity_type, target_entity_id, source_file, source_log_hash)
        values (timestamp with time zone '2026-08-02 01:00:10+00:00', 'PlayerA', 1, 'zombieA', 11, 'log', 'kill-minute-1'),
               (timestamp with time zone '2026-08-02 01:00:40+00:00', 'PlayerA', 1, 'zombieB', 12, 'log', 'kill-minute-2'),
               (timestamp with time zone '2026-08-02 01:01:05+00:00', 'PlayerA', 1, 'zombieC', 13, 'log', 'kill-minute-3')
        """);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.travelEntries())
        .filteredOn(entry -> "PlayerA".equals(entry.actor()))
        .hasSize(2);
  }

  @Test
  void detailViewsExposeServerKillAndVehicleData() {
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA')
        """);
    jdbcTemplate.update("""
        insert into t_server_metric
        (occurred_at, fps, zombie_count, entity_count, rss_mb, source_file, source_log_hash)
        values (timestamp with time zone '2026-08-02 01:00:00+00:00', 19.5, 8, 20, 2048, 'log', 'metric-detail')
        """);
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, target_entity_type, target_entity_id, source_file, source_log_hash)
        values (timestamp with time zone '2026-08-02 01:00:00+00:00', 'PlayerA', 1, 'zombieA', 11, 'log', 'kill-detail')
        """);
    jdbcTemplate.update("""
        insert into t_vehicle_current_state
        (vehicle_entity_id, vehicle_type, vehicle_name, owner_player_id, total_distance, active, last_updated, source_file, source_log_hash)
        values (99, 'EntityMotorcycle', 'vehicleMotorcycle', 1, 42.0, true,
                timestamp with time zone '2026-08-02 01:00:00+00:00', 'log', 'vehicle-detail')
        """);
    jdbcTemplate.update("""
        insert into t_vehicle_position_transaction
        (occurred_at, event_type, vehicle_entity_id, vehicle_type, vehicle_name,
         attributed_player_id, attribution_method, movement_valid, position_x, position_z,
         movement_distance, source_file, source_log_hash, raw_line)
        values (timestamp with time zone '2026-08-02 01:00:00+00:00', 'VEHICLE_WRITE', 99,
                'EntityMotorcycle', 'vehicleMotorcycle', 1, 'online_near_vehicle_position', true,
                10, 10, 42.0, 'log', 'vehicle-detail-movement', 'raw')
        """);

    assertThat(dashboardViewService.serverDetail().history()).hasSize(1);
    assertThat(dashboardViewService.serverDetail().health().label()).isEqualTo("安定稼働");
    assertThat(dashboardViewService.killDetail().recentKills()).hasSize(1);
    assertThat(dashboardViewService.vehicleDetail().vehicles()).hasSize(1);
  }

  @Test
  void serverHealthHighlightsLowFpsAndRapidMemoryGrowth() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into t_server_metric
        (occurred_at, fps, zombie_count, entity_count, rss_mb, source_file, source_log_hash)
        values (?, 20, 2, 10, 1000, 'log', 'health-stable'),
               (?, 10, 20, 80, 1400, 'log', 'health-unstable')
        """, now.minusMinutes(2), now.minusMinutes(1));

    DashboardViewService.ServerHealth health = dashboardViewService.serverDetail().health();

    assertThat(health.level()).isEqualTo("unstable");
    assertThat(health.minimumFps()).isEqualByComparingTo("10");
    assertThat(health.incidents()).isNotEmpty();
    assertThat(health.fpsChartPoints()).isNotBlank();
    assertThat(health.rssChartPoints()).isNotBlank();
  }

  @Test
  void vehicleRankingAggregatesByDriverAndTypeAndHidesUnattributedNoise() {
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA')
        """);
    jdbcTemplate.update("""
        insert into t_vehicle_current_state
        (vehicle_entity_id, vehicle_type, vehicle_name, owner_player_id, total_distance, active,
         last_updated, source_file, source_log_hash)
        values (10, 'EntityBicycle', '自転車', 1, 120.0, true, current_timestamp, 'log', 'bike-a'),
               (11, 'EntityBicycle', '自転車', 1, 80.0, false, current_timestamp, 'log', 'bike-b'),
               (12, 'EntityBicycle', '自転車', null, 9999.0, true, current_timestamp, 'log', 'bike-noise')
        """);
    jdbcTemplate.update("""
        insert into t_vehicle_position_transaction
        (occurred_at, event_type, vehicle_entity_id, vehicle_type, vehicle_name,
         attributed_player_id, attribution_method, movement_valid, movement_distance,
         source_file, source_log_hash, raw_line)
        values (current_timestamp, 'VEHICLE_WRITE', 10, 'EntityBicycle', '自転車', 1,
                'online_near_vehicle_position', true, 120.0, 'log', 'bike-a-move', 'raw'),
               (current_timestamp, 'VEHICLE_WRITE', 11, 'EntityBicycle', '自転車', 1,
                'online_near_vehicle_position', true, 80.0, 'log', 'bike-b-move', 'raw'),
               (current_timestamp, 'VEHICLE_WRITE', 12, 'EntityBicycle', '自転車', null,
                null, true, 9999.0, 'log', 'bike-noise-move', 'raw')
        """);

    DashboardViewService.VehicleDetailView detail = dashboardViewService.vehicleDetail();

    assertThat(detail.vehicles()).hasSize(1);
    assertThat(detail.vehicles().getFirst().driverName()).isEqualTo("PlayerA");
    assertThat(detail.vehicles().getFirst().vehicleCount()).isEqualTo(2);
    assertThat(detail.vehicles().getFirst().totalDistance()).isEqualByComparingTo("200.0");
    assertThat(detail.summary().vehicleCount()).isEqualTo(2);
    assertThat(detail.summary().activeCount()).isEqualTo(1);
    assertThat(detail.summary().driverCount()).isEqualTo(1);
    assertThat(detail.typeRankings()).singleElement()
        .satisfies(type -> assertThat(type.totalDistance()).isEqualByComparingTo("200.0"));
  }

  @Test
  void timelineShowsOwnedVehicleMovementButNotAmbiguousPlayerMovement() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA')
        """);
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, player_id, position_x, position_y, position_z,
         position_source_type, movement_distance, source_event_hash, source_file)
        values (?, 'PlayerA', 101, 1, 100, 30, 100, 'LP_COMMAND', 25.0, 'ambiguous-walk', 'telnet')
        """, now.minusMinutes(2));
    jdbcTemplate.update("""
        insert into t_vehicle_position_transaction
        (occurred_at, event_type, vehicle_entity_id, vehicle_type, vehicle_name, owner_player_id,
         attributed_player_id, attribution_method, movement_valid,
         position_x, position_y, position_z, movement_distance, source_file, source_log_hash, raw_line)
        values (?, 'VEHICLE_LOADED', 99, 'EntityMotorcycle', 'オートバイ', 1,
                1, 'online_near_vehicle_position', true,
                200, 30, 200, 42.5, 'log', 'vehicle-move-only', 'raw')
        """, now.minusMinutes(1));

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.travelEntries())
        .extracting(DashboardViewService.TravelEntry::kind)
        .contains("VEHICLE_MOVE")
        .doesNotContain("MOVE");
    assertThat(dashboard.travelEntries().getFirst().message())
        .contains("オートバイに乗って42.5 m移動");
  }

  @Test
  void timelineGroupsSimultaneousSleepersPerNearestPlayer() {
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, position_x, position_y, position_z,
         position_source_type, source_event_hash, source_file)
        values (?, 'PlayerA', 101, 0, 40, 0, 'LP_COMMAND', 'group-player-a', 'telnet'),
               (?, 'PlayerB', 202, 500, 40, 500, 'LP_COMMAND', 'group-player-b', 'telnet')
        """, occurredAt, occurredAt);
    jdbcTemplate.update("""
        insert into t_sleeper_transaction
        (occurred_at, transaction_type, sleeper_volume_x, sleeper_volume_y, sleeper_volume_z,
         position_x, position_y, position_z, entity_class, entity_count, source_file, source_log_hash)
        values (?, 'SLEEPER_SPAWN', 0, 40, 0, 5, 40, 5, 'zombieNurse', 1, 'log', 'group-a1'),
               (?, 'SLEEPER_SPAWN', 0, 40, 0, 6, 40, 5, 'zombieBoe', 1, 'log', 'group-a2'),
               (?, 'SLEEPER_SPAWN', 0, 40, 0, 5, 40, 6, 'zombieMoe', 1, 'log', 'group-a3'),
               (?, 'SLEEPER_SPAWN', 0, 40, 0, 6, 40, 6, 'zombieJoe', 1, 'log', 'group-a4'),
               (?, 'SLEEPER_SPAWN', 500, 40, 500, 505, 40, 505, 'zombieNurse', 1, 'log', 'group-b1'),
               (?, 'SLEEPER_SPAWN', 500, 40, 500, 506, 40, 505, 'zombieBoe', 1, 'log', 'group-b2')
        """, occurredAt, occurredAt, occurredAt, occurredAt, occurredAt, occurredAt);

    List<DashboardViewService.TravelEntry> sleepers = dashboardViewService.dashboard().travelEntries().stream()
        .filter(entry -> "SLEEPER_SPAWN".equals(entry.kind()))
        .toList();

    assertThat(sleepers).hasSize(2);
    assertThat(sleepers).extracting(DashboardViewService.TravelEntry::actor)
        .containsExactlyInAnyOrder("PlayerA", "PlayerB");
    assertThat(sleepers).filteredOn(entry -> "PlayerA".equals(entry.actor())).singleElement()
        .satisfies(entry -> assertThat(entry.message()).contains("大量の敵（4体）"));
  }

  @Test
  void explorationMarksPoiVisitedWhenPositionPassedWithinEightyMeters() {
    jdbcTemplate.update("""
        insert into m_world_poi
        (source_path, source_hash, world_name, poi_name, category, x, y, z)
        values ('world', 'poi-near', 'World', 'store_book_01', 'trader', 100, 30, 100),
               ('world', 'poi-far', 'World', 'factory_01', 'industrial', 1000, 30, 1000)
        """);
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, position_x, position_y, position_z,
         position_source_type, source_event_hash, source_file)
        values (current_timestamp, 'PlayerA', 1, 140, 30, 130, 'LP_COMMAND', 'poi-visit', 'telnet')
        """);

    DashboardViewService.ExplorationDetailView detail = dashboardViewService.explorationDetail();

    assertThat(detail.totalCount()).isEqualTo(2);
    assertThat(detail.exploredCount()).isEqualTo(1);
    assertThat(detail.unexploredCount()).isEqualTo(1);
    assertThat(detail.pois()).hasSize(1).allMatch(DashboardViewService.PoiExploration::explored);
    assertThat(detail.pois().getFirst().visitorName()).isEqualTo("PlayerA");
  }

  @Test
  void killInsightsContainEnemyRankingDailyKillsAndXpGrowth() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, target_entity_type, target_entity_id,
         source_file, source_log_hash)
        values (?, 'PlayerA', 1, 'zombieBiker', 10, 'log', 'insight-kill-a'),
               (?, 'PlayerB', 2, 'zombieBiker', 11, 'log', 'insight-kill-b')
        """, now.minusHours(1), now.minusHours(2));
    jdbcTemplate.update("""
        insert into t_level_xp_summary_transaction
        (occurred_at, player_name, player_entity_id, xp_from_loot, xp_from_harvesting,
         xp_from_kill, xp_total, source_file, source_log_hash)
        values (?, 'PlayerA', 1, 20, 30, 50, 100, 'log', 'insight-xp')
        """, now.minusMinutes(30));

    DashboardViewService.KillDetailView detail = dashboardViewService.killDetail();

    assertThat(detail.defeatedEnemies().getFirst().defeatedCount()).isEqualTo(2);
    assertThat(detail.dailyKills()).isNotEmpty();
    assertThat(detail.growthTrend().killXp()).isEqualTo(50);
    assertThat(detail.growthTrend().harvestXp()).isEqualTo(30);
    assertThat(detail.growthTrend().lootXp()).isEqualTo(20);
    assertThat(detail.growthTrend().totalXp()).isEqualTo(100);
    assertThat(detail.growthTrend().totalChartPoints()).isNotBlank();
    assertThat(detail.scoreChart().series()).isNotEmpty();
  }

  @Test
  void adventureRankingCombinesKillsTravelAndCompletedPlaySessions() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA', ?)
        """, Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, player_id, position_x, position_y, position_z,
         position_source_type, inference_method, movement_distance, movement_mode,
         source_event_hash, source_file)
        values (?, 'PlayerA', 101, 1, 0, 0, 0, 'LP_COMMAND', 'direct_telnet_lp', 250.0,
                'ON_FOOT', 'ranking-position', 'telnet:lp')
        """, now.minusMinutes(1));
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, player_id, target_entity_type, target_entity_id,
         source_file, source_log_hash)
        values (?, 'PlayerA', 101, 1, 'zombieA', 11, 'log', 'ranking-kill')
        """, now.minusMinutes(2));
    jdbcTemplate.update("""
        insert into t_player_join_transaction
        (occurred_at, player_name, player_entity_id, player_id, join_reason, source_file, source_log_hash)
        values (?, 'PlayerA', 101, 1, 'JoinMultiplayer', 'log', 'ranking-join')
        """, now.minusMinutes(65));
    jdbcTemplate.update("""
        insert into t_player_join_transaction
        (occurred_at, player_name, player_entity_id, player_id, join_reason, source_file, source_log_hash)
        values (?, 'PlayerA', 101, 1, 'Died', 'log', 'ranking-fake-respawn')
        """, now.minusMinutes(35));
    jdbcTemplate.update("""
        insert into t_player_leave_transaction
        (occurred_at, player_name, player_entity_id, player_id, source_file, source_log_hash)
        values (?, 'PlayerA', 101, 1, 'log', 'ranking-leave')
        """, now.minusMinutes(5));

    DashboardViewService.KillDetailView detail = dashboardViewService.killDetail();

    assertThat(detail.rankings()).hasSize(1);
    assertThat(detail.rankings().getFirst().kills()).isEqualTo(1);
    assertThat(detail.rankings().getFirst().travelDistance()).isEqualByComparingTo("250.0");
    assertThat(detail.rankings().getFirst().playMinutes()).isEqualTo(60);
    assertThat(detail.dailyActivity()).isNotEmpty();
  }

  @Test
  void adventureRankingIncludesCurrentOnlineSession() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA', ?)
        """, Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_join_transaction
        (occurred_at, player_name, player_entity_id, player_id, join_reason, source_file, source_log_hash)
        values (?, 'PlayerA', 101, 1, 'EnterMultiplayer', 'log', 'online-session-join')
        """, now.minusMinutes(30));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_id, player_name, position_x, position_z, online, last_updated)
        values (101, 1, 'PlayerA', 0, 0, true, ?)
        """, now);

    DashboardViewService.KillDetailView detail = dashboardViewService.killDetail();

    assertThat(detail.rankings()).hasSize(1);
    assertThat(detail.rankings().getFirst().playMinutes()).isGreaterThanOrEqualTo(30);
  }

  @Test
  void playerStatusShowsNearbyOwnedVehicleAsCurrentRide() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA', ?)
        """, Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_id, player_name, position_x, position_y, position_z, online, last_updated)
        values (101, 1, 'PlayerA', 100, 30, 100, true, ?)
        """, now.minusSeconds(5));
    jdbcTemplate.update("""
        insert into t_vehicle_current_state
        (vehicle_entity_id, vehicle_type, vehicle_name, owner_player_id, position_x, position_y, position_z,
         total_distance, active, last_updated, source_file, source_log_hash)
        values (99, 'EntityMotorcycle', 'vehicleMotorcycle', 1, 104, 30, 100,
                42.0, true, ?, 'log', 'nearby-current-vehicle')
        """, now.minusSeconds(4));

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.playerStatuses().getFirst().currentVehicle()).isEqualTo("vehicleMotorcycle");
  }

  @Test
  void playerStatusInfersExplorationAfterThreeMinutesNearTheSamePosition() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'StationaryPlayer', ?),
               (2, 'EOS:eos-b', 'EOS', 'eos-b', 'MovingPlayer', ?)
        """, Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_id, player_name, position_x, position_y, position_z, online, last_updated)
        values (101, 1, 'StationaryPlayer', 105, 30, 100, true, ?),
               (102, 2, 'MovingPlayer', 200, 30, 200, true, ?)
        """, now, now);
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, player_id, position_x, position_y, position_z,
         position_source_type, inference_method, movement_distance, movement_mode,
         source_event_hash, source_file)
        values (?, 'StationaryPlayer', 101, 1, 100, 30, 100, 'LP_COMMAND', 'direct', 0, 'ON_FOOT', 'stationary-old', 'telnet:lp'),
               (?, 'StationaryPlayer', 101, 1, 108, 30, 101, 'LP_COMMAND', 'direct', 0, 'ON_FOOT', 'stationary-mid', 'telnet:lp'),
               (?, 'StationaryPlayer', 101, 1, 105, 30, 100, 'LP_COMMAND', 'direct', 0, 'ON_FOOT', 'stationary-new', 'telnet:lp'),
               (?, 'MovingPlayer', 102, 2, 190, 30, 190, 'LP_COMMAND', 'direct', 0, 'ON_FOOT', 'moving-old', 'telnet:lp'),
               (?, 'MovingPlayer', 102, 2, 100, 30, 100, 'LP_COMMAND', 'direct', 0, 'ON_FOOT', 'moving-mid', 'telnet:lp'),
               (?, 'MovingPlayer', 102, 2, 200, 30, 200, 'LP_COMMAND', 'direct', 0, 'ON_FOOT', 'moving-new', 'telnet:lp')
        """, now.minusMinutes(4), now.minusMinutes(2), now,
        now.minusMinutes(4), now.minusMinutes(2), now);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    DashboardViewService.PlayerStatus stationary = dashboard.playerStatuses().stream()
        .filter(status -> "StationaryPlayer".equals(status.playerName()))
        .findFirst().orElseThrow();
    DashboardViewService.PlayerStatus moving = dashboard.playerStatuses().stream()
        .filter(status -> "MovingPlayer".equals(status.playerName()))
        .findFirst().orElseThrow();
    assertThat(stationary.exploring()).isTrue();
    assertThat(stationary.statusSummary()).contains("探索中");
    assertThat(moving.exploring()).isFalse();
    assertThat(moving.statusSummary()).contains("移動中");
  }

  @Test
  void playerStatusesShowOneRowForSameExternalPlayer() {
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, native_platform, native_user_id, player_name, last_seen_at)
        values (1, 'Steam:76561198382915826', 'Steam', '76561198382915826', null, null, 'PlayerOld', timestamp '2026-07-25 10:00:00')
        """);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, native_platform, native_user_id, player_name, last_seen_at)
        values (2, 'EOS:00024b5c4d2546468b7c6775bd927c32', 'EOS', '00024b5c4d2546468b7c6775bd927c32', 'Steam', '76561198382915826', 'PlayerNew', timestamp '2026-07-26 10:00:00')
        """);
    jdbcTemplate.update("""
        insert into t_player_state_snapshot (player_id, world_name, game_name, captured_at, last_login, x, y, z, source_hash)
        values (2, 'World', 'Game', timestamp '2026-07-26 10:00:00', timestamp '2026-07-26 09:00:00', 10, 20, 30, 'snapshot-1')
        """);
    jdbcTemplate.update("""
        insert into t_player_state_snapshot (player_id, world_name, game_name, captured_at, last_login, x, y, z, source_hash)
        values (2, 'World', 'Game', timestamp '2026-07-26 11:00:00', timestamp '2026-07-26 10:00:00', 11, 21, 31, 'snapshot-2')
        """);
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_name, position_x, position_y, position_z, platform_id, cross_platform_id, online, last_updated)
        values (331, 'PlayerNew', 12, 22, 32, 'Steam_76561198382915826', 'EOS_00024b5c4d2546468b7c6775bd927c32', true, timestamp with time zone '2026-07-26 01:30:00+00:00')
        """);
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_name, position_x, position_y, position_z, platform_id, cross_platform_id, online, last_updated)
        values (332, 'PlayerNew', 13, 23, 33, 'Steam_76561198382915826', null, true, timestamp with time zone '2026-07-26 01:00:00+00:00')
        """);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.playerStatuses())
        .extracting(DashboardViewService.PlayerStatus::playerName)
        .containsExactly("PlayerNew");
    assertThat(dashboard.playerStatuses().getFirst().coordinate()).isEqualTo("12, 22, 32");
  }

  @Test
  void playerStatusesUseLatestCurrentStateForHealthAndOnlineDisplay() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, native_platform, native_user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'Steam', 'steam-a', 'PlayerA', ?)
        """, Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_state_snapshot (player_id, world_name, game_name, captured_at, last_login, x, y, z, source_hash)
        values (1, 'World', 'Game', ?, ?, 10, 20, 30, 'snapshot-latest-current-state')
        """, Timestamp.from(now.minusSeconds(60).toInstant()), Timestamp.from(now.minusSeconds(60).toInstant()));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_name, position_x, position_y, position_z, health, level, platform_id, cross_platform_id, online, last_updated)
        values (101, 'PlayerA', 1, 2, 3, 10, 1, 'Steam_steam-a', 'EOS_eos-a', true, ?)
        """, now.minusSeconds(90));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_name, position_x, position_y, position_z, health, level, platform_id, cross_platform_id, online, last_updated)
        values (303, 'PlayerA', 4, 5, 6, 88, 3, 'Steam_steam-a', 'EOS_eos-a', true, ?)
        """, now.minusSeconds(5));

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.playerStatuses()).hasSize(1);
    assertThat(dashboard.playerStatuses().getFirst().health()).isEqualTo(88);
    assertThat(dashboard.playerStatuses().getFirst().level()).isEqualTo(3);
    assertThat(dashboard.playerStatuses().getFirst().coordinate()).isEqualTo("4, 5, 6");
    assertThat(dashboard.playerStatuses().getFirst().online()).isTrue();
  }

  @Test
  void playerDetailUsesCurrentStateMatchedByExternalIdentity() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, native_platform, native_user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'Steam', 'steam-a', 'PlayerA', ?)
        """, Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_state_snapshot (player_id, world_name, game_name, captured_at, last_login, x, y, z, source_hash)
        values (1, 'World', 'Game', ?, ?, 10, 20, 30, 'snapshot-detail-current-state')
        """, Timestamp.from(now.minusSeconds(90).toInstant()), Timestamp.from(now.minusSeconds(90).toInstant()));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_name, position_x, position_y, position_z, health, deaths, level, ping,
         platform_id, cross_platform_id, online, last_updated)
        values (777, 'PlayerA', 40, 50, 60, 123, 2, 9, 6, 'Steam_steam-a', 'EOS_eos-a', true, ?)
        """, now.minusSeconds(5));

    DashboardViewService.PlayerDetailView detail = dashboardViewService.playerDetail(1L).orElseThrow();

    assertThat(detail.status().health()).isEqualTo(123);
    assertThat(detail.status().online()).isTrue();
    assertThat(detail.status().coordinate()).isEqualTo("40, 50, 60");
    assertThat(detail.insights().kills()).isZero();
    assertThat(detail.insights().favoriteVehicle()).isEqualTo("未記録");
  }

  @Test
  void poiFallbackUsesJapaneseWordsForKnownPoiTokens() {
    assertThat(poiNameService.displayName("countrytown_business_01"))
        .isEqualTo("田舎町 事務所");
  }

  @Test
  void poiNamesAndCategoriesAreLocalizedForExplorationDisplay() {
    assertThat(poiNameService.displayName("base_military_01")).isEqualTo("軍事基地");
    assertThat(poiNameService.displayName("base_survivor_01")).isEqualTo("生存者基地");
    assertThat(poiNameService.displayName("house_old_bungalow_02")).isEqualTo("旧式の平屋住宅");
    assertThat(poiNameService.displayName("rwg_tile_rural_corner_01"))
        .isEqualTo("道路生成 区画 農村 角地");
    assertThat(poiNameService.displayName("rwg_tile_gateway_t_01"))
        .isEqualTo("道路生成 区画 入口 T字路");
    assertThat(poiNameService.displayCategory("rwg")).isEqualTo("道路区画");
    assertThat(poiNameService.displayCategory("cemetery")).isEqualTo("墓地");
  }

  @Test
  void playerStatusesTreatStaleCurrentStateAsOffline() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA', ?)
        """, Timestamp.from(now.toInstant()));
    jdbcTemplate.update("""
        insert into t_player_state_snapshot (player_id, world_name, game_name, captured_at, last_login, x, y, z, source_hash)
        values (1, 'World', 'Game', ?, ?, 10, 20, 30, 'snapshot-stale-current-state')
        """, Timestamp.from(now.minusSeconds(300).toInstant()), Timestamp.from(now.minusSeconds(300).toInstant()));
    jdbcTemplate.update("""
        insert into t_player_current_state
        (player_entity_id, player_name, position_x, position_y, position_z, health, platform_id, cross_platform_id, online, last_updated)
        values (101, 'PlayerA', 1, 2, 3, 10, 'Steam_steam-a', 'EOS_eos-a', true, ?)
        """, now.minusSeconds(300));

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.playerStatuses()).hasSize(1);
    assertThat(dashboard.playerStatuses().getFirst().online()).isFalse();
  }

  @Test
  void killMessageContainsKillerAndVictim() {
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, target_entity_type, target_entity_id, source_file, source_log_hash)
        values (timestamp with time zone '2026-07-26 01:22:51+00:00', 'DDD烈火王テムジン', 331, 'zombieBusinessMan', 347, 'log', 'kill-1')
        """);
    jdbcTemplate.update("""
        insert into m_japanese_translation (localization_key, display_text)
        values ('zombieBusinessMan', 'ショー')
        """);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.travelEntries().getFirst().message())
        .contains("DDD烈火王テムジン")
        .contains("ショー");
  }

  @Test
  void displayTimeFormatsUtcOffsetDateTimeAsJstAndKeepsLocalDateTimeLocal() {
    DisplayTimeFormatter formatter = new DisplayTimeFormatter();

    assertThat(formatter.format(OffsetDateTime.of(2026, 7, 26, 15, 8, 38, 0, ZoneOffset.UTC)))
        .isEqualTo("2026-07-27 00:08:38");
    assertThat(formatter.format(OffsetDateTime.of(2026, 7, 26, 1, 8, 38, 0, ZoneOffset.UTC)))
        .isEqualTo("2026-07-26 10:08:38");
    assertThat(formatter.format(Timestamp.valueOf(LocalDateTime.of(2026, 7, 26, 14, 8, 38))))
        .isEqualTo("2026-07-26 14:08:38");
  }

  @Test
  void killMessageWithoutVictimDoesNotThrow() {
    EventMessageFormatter formatter = new EventMessageFormatter();

    assertThat(formatter.format("KILL", "DDD烈火王テムジン", "討伐した", null, null))
        .contains("DDD烈火王テムジン")
        .contains("討伐した");
  }

  @Test
  void dashboardOmitsDailyDiaryCardWhenNoDiaryIsPublished() {
    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.aiComment()).isNull();
  }

  @Test
  void timelineKeepsKillEventsAndOmitsSleeperRestoreNoise() {
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, target_entity_type, target_entity_id, source_file, source_log_hash)
        values (timestamp with time zone '2026-07-26 01:22:51+00:00', 'DDD烈火王テムジン', 331, 'zombieBusinessMan', 347, 'log', 'kill-restore-test')
        """);
    jdbcTemplate.update("""
        insert into t_sleeper_transaction
        (occurred_at, transaction_type, sleeper_volume_x, sleeper_volume_y, sleeper_volume_z,
         position_x, position_y, position_z, entity_class, source_file, source_log_hash)
        values (timestamp with time zone '2026-07-26 01:22:52+00:00', 'SLEEPER_RESTORE', 1, 2, 3,
                4, 5, 6, 'zombieBoe', 'log', 'restore-1')
        """);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.travelEntries())
        .extracting(DashboardViewService.TravelEntry::kind)
        .contains("KILL")
        .doesNotContain("SLEEPER_RESTORE");
  }

  @Test
  void dashboardShowsWorldAndVehicleEvents() {
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:eos-a', 'EOS', 'eos-a', 'PlayerA', timestamp '2026-07-26 10:00:00')
        """);
    jdbcTemplate.update("""
        insert into t_world_event_transaction
        (occurred_at, event_type, actor_player_name, detail_text, position_x, position_y, position_z,
         source_file, source_log_hash, raw_line)
        values (timestamp with time zone '2026-07-29 05:07:38+00:00', 'AIR_DROP', null, '補給物資', 460, 209, 33,
                'log', 'airdrop-1', 'raw')
        """);
    jdbcTemplate.update("""
        insert into t_vehicle_current_state
        (vehicle_entity_id, vehicle_type, vehicle_name, owner_player_id, owner_cross_platform_id,
         owner_inference_method, position_x, position_y, position_z, total_distance, active,
         last_updated, source_file, source_log_hash)
        values (2631, 'EntityBicycle', 'vehicleBicycle', 1, 'EOS_eos-a', 'nearest_fresh_player_position',
                452, 38, -605, 20.0, true,
                timestamp with time zone '2026-07-29 05:58:16+00:00', 'log', 'vehicle-current')
        """);
    jdbcTemplate.update("""
        insert into t_vehicle_position_transaction
        (occurred_at, event_type, vehicle_entity_id, vehicle_type, vehicle_name, owner_player_id,
         attributed_player_id, attribution_method, movement_valid,
         position_x, position_y, position_z, movement_distance, source_file, source_log_hash, raw_line)
        values (timestamp with time zone '2026-07-29 05:56:11+00:00', 'VEHICLE_LOADED', 2631, 'EntityBicycle',
                'vehicleBicycle', 1, 1, 'online_near_vehicle_position', true,
                442, 38, -615, 20.0, 'log', 'vehicle-loaded', 'raw')
        """);
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, player_id, position_x, position_y, position_z,
         position_source_type, inference_method, movement_distance, movement_mode,
         source_event_hash, source_file)
        values (timestamp with time zone '2026-07-29 05:58:10+00:00', 'PlayerA', 171, 1, 450, 38, -605,
                'LP_COMMAND', 'direct_telnet_lp', 12.5, 'ON_FOOT', 'player-position', 'telnet:lp')
        """);

    DashboardViewService.DashboardView dashboard = dashboardViewService.dashboard();

    assertThat(dashboard.travelEntries())
        .extracting(DashboardViewService.TravelEntry::kind)
        .contains("AIR_DROP", "VEHICLE_MOVE")
        .doesNotContain("MOVE");
    assertThat(dashboard.vehicleStatuses()).hasSize(1);
    assertThat(dashboard.vehicleStatuses().getFirst().driverName()).isEqualTo("PlayerA");
    assertThat(dashboard.vehicleStatuses().getFirst().vehicleCount()).isEqualTo(1);
    assertThat(dashboard.vehicleStatuses().getFirst().totalDistance()).isEqualByComparingTo("20.0");
    assertThat(dashboard.playerStatuses()).hasSize(1);
    assertThat(dashboard.playerStatuses().getFirst().travelDistance()).isEqualByComparingTo("12.5");
    assertThat(dashboard.playerStatuses().getFirst().vehicleDistance()).isEqualByComparingTo("20.0");
  }
}
