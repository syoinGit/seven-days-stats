package com.yuki.sevendays_states.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sevendays_states_diary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class DiaryMaintenanceServiceTests {

  @Autowired
  private DiaryMaintenanceService service;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetData() {
    jdbcTemplate.update("delete from t_ai_comment");
    jdbcTemplate.update("delete from t_world_time_observation");
    jdbcTemplate.update("delete from t_entity_kill_transaction");
    jdbcTemplate.update("delete from t_level_xp_summary_transaction");
    jdbcTemplate.update("delete from t_world_event_transaction");
    jdbcTemplate.update("delete from t_vehicle_position_transaction");
    jdbcTemplate.update("delete from t_vehicle_current_state");
    jdbcTemplate.update("delete from t_player_position_transaction");
    jdbcTemplate.update("delete from t_player_join_transaction");
    jdbcTemplate.update("delete from t_player_current_state");
    jdbcTemplate.update("delete from m_player");
    jdbcTemplate.update("delete from m_world_poi");
  }

  @Test
  void buildsDailyGenerationPacketFromAdventureLogs() {
    LocalDate date = LocalDate.of(2026, 8, 2);
    OffsetDateTime time = OffsetDateTime.of(2026, 8, 2, 3, 0, 0, 0, ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into t_world_time_observation
        (observed_at, game_day, game_hour, game_minute, source, source_hash, raw_response)
        values (?, 20, 12, 0, 'telnet:gettime', 'diary-time', 'Day 20, 12:00')
        """, time);
    jdbcTemplate.update("""
        insert into t_player_join_transaction
        (occurred_at, player_name, player_entity_id, source_file, source_log_hash)
        values (?, 'PlayerA', 101, 'log', 'diary-join')
        """, time);
    jdbcTemplate.update("""
        insert into m_world_poi
        (source_path, source_hash, world_name, poi_name, category, x, y, z)
        values ('world', 'diary-poi-start', 'World', 'hospital_01', 'hospital', 10, 40, 20),
               ('world', 'diary-poi-end', 'World', 'farm_01', 'farm', 200, 40, 220)
        """);
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, position_x, position_y, position_z,
         position_source_type, source_event_hash, source_file, movement_distance)
        values (?, 'PlayerA', 101, 10, 40, 20, 'LP_COMMAND', 'diary-pos', 'telnet', 125.5),
               (?, 'PlayerA', 101, 200, 40, 220, 'LP_COMMAND', 'diary-pos-end', 'telnet', 0)
        """, time.plusMinutes(1), time.plusMinutes(4));
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, target_entity_type, target_entity_id,
         source_file, source_log_hash)
        values (?, 'PlayerA', 101, 'zombieNurse', 501, 'log', 'diary-kill')
        """, time.plusMinutes(2));
    jdbcTemplate.update("""
        insert into t_level_xp_summary_transaction
        (occurred_at, player_name, player_entity_id, xp_from_loot, xp_from_harvesting,
         xp_from_kill, xp_total, source_file, source_log_hash)
        values (?, 'PlayerA', 101, 20, 30, 50, 100, 'log', 'diary-xp')
        """, time.plusMinutes(3));
    jdbcTemplate.update("""
        insert into t_world_event_transaction
        (occurred_at, event_type, detail_text, source_file, source_log_hash, raw_line)
        values (?, 'BLOOD_MOON', 'Day 21 / 周期 7', 'log', 'diary-blood', 'BloodMoon SetDay')
        """, time.minusMinutes(1));

    DiaryMaintenanceService.DiaryPacket packet = service.packet(date);

    assertThat(packet.gameDayLabel()).isEqualTo("DAY 20");
    assertThat(packet.participants()).singleElement().satisfies(player -> {
      assertThat(player.name()).isEqualTo("PlayerA");
      assertThat(player.kills()).isOne();
      assertThat(player.positionDistance()).isEqualByComparingTo("125.5");
      assertThat(player.startPlace()).contains("病院");
      assertThat(player.endPlace()).contains("農場");
    });
    assertThat(packet.xp().total()).isEqualTo(100);
    assertThat(packet.bloodMoon().status()).contains("あと1日");
    assertThat(packet.generationData()).contains(
        "PlayerA", "討伐1", "位置移動125.5m", "SLEEPER_SPAWNは戦闘数や一斉出現数ではなく",
        "開始地点", "終了地点", "討伐XP: 50", "採取XP: 30", "探索・物資XP: 20",
        "Blood Moonまであと1日",
        "現在のログには建築専用XPがない", "最も印象的な出来事を一つ選び",
        "訪問POIを一覧として説明しない", "プレイヤー間に順位や優劣を付けない",
        "事実の根拠として使う", "締め方は毎回変える");
    assertThat(service.days()).extracting(DiaryMaintenanceService.DiaryDay::date).contains(date);
  }

  @Test
  void countsOnlyVerifiedVehicleDrivingForTheDiary() {
    LocalDate date = LocalDate.of(2026, 8, 2);
    OffsetDateTime time = OffsetDateTime.of(2026, 8, 2, 3, 0, 0, 0, ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name)
        values (1, 'EOS:driver', 'EOS', 'driver', 'Driver'),
               (2, 'EOS:passenger', 'EOS', 'passenger', 'Passenger')
        """);
    jdbcTemplate.update("""
        insert into t_player_join_transaction
        (occurred_at, player_name, player_entity_id, player_id, source_file, source_log_hash)
        values (?, 'Driver', 101, 1, 'log', 'driver-join'),
               (?, 'Passenger', 102, 2, 'log', 'passenger-join')
        """, time, time);
    jdbcTemplate.update("""
        insert into t_vehicle_position_transaction
        (occurred_at, event_type, vehicle_entity_id, vehicle_type, vehicle_name, owner_player_id,
         attributed_player_id, attribution_method, movement_valid, movement_distance,
         source_file, source_log_hash, raw_line)
        values (?, 'VEHICLE_WRITE', 4001, 'EntityVJeep', 'vehicleTruck4x4', 2,
                1, 'online_near_vehicle_position', true, 40.0, 'log', 'verified-drive', 'raw'),
               (?, 'VEHICLE_WRITE', 4001, 'EntityVJeep', 'vehicleTruck4x4', 2,
                1, 'online_near_vehicle_position', false, 999.0, 'log', 'invalid-drive', 'raw')
        """, time.plusMinutes(1), time.plusMinutes(2));

    DiaryMaintenanceService.DiaryPacket packet = service.packet(date);

    assertThat(packet.participants()).extracting(DiaryMaintenanceService.PlayerDay::name)
        .containsExactly("Driver", "Passenger");
    assertThat(packet.participants()).filteredOn(player -> player.name().equals("Driver"))
        .singleElement().satisfies(player ->
            assertThat(player.vehicleDistance()).isEqualByComparingTo("40.0"));
    assertThat(packet.participants()).filteredOn(player -> player.name().equals("Passenger"))
        .singleElement().satisfies(player ->
            assertThat(player.vehicleDistance()).isEqualByComparingTo("0"));
  }
}
