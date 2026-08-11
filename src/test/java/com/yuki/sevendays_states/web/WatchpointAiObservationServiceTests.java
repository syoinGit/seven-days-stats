package com.yuki.sevendays_states.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:watchpoint_ai_observation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.ai.window-minutes=30",
    "app.ai.max-events=20"
})
class WatchpointAiObservationServiceTests {

  @Autowired
  private WatchpointAiObservationService service;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void buildsGroundedThirtyMinutePayloadWithoutRawIdentifiers() throws Exception {
    OffsetDateTime at = OffsetDateTime.of(2026, 8, 5, 12, 0, 0, 0, ZoneOffset.UTC);
    jdbcTemplate.update("""
        insert into m_player (id, player_key, platform, user_id, player_name, last_seen_at)
        values (1, 'EOS:private-id', 'EOS', 'private-id', 'SurvivorA', ?)
        """, at);
    jdbcTemplate.update("""
        insert into m_world_poi
        (source_hash, world_name, game_name, poi_name, poi_type, category, x, y, z)
        values ('poi-ai-1', 'World', 'Game', 'house_old_bungalow_02', 'Prefab', 'house', 100, 30, 100)
        """);
    jdbcTemplate.update("""
        insert into t_player_join_transaction
        (occurred_at, player_name, player_entity_id, player_id, platform_id, cross_platform_id,
         join_reason, source_file, source_log_hash)
        values (?, 'SurvivorA', 101, 1, 'Steam_PRIVATE', 'EOS_PRIVATE',
                'EnterMultiplayer', '/private/log', 'ai-join-current'),
               (?, 'SurvivorA', 101, 1, 'Steam_PRIVATE', 'EOS_PRIVATE',
                'EnterMultiplayer', '/private/log', 'ai-join-comparison')
        """, at.minusMinutes(20), at.minusMinutes(45));
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
        (occurred_at, player_name, player_entity_id, player_id, target_entity_type, target_entity_id,
         source_file, source_log_hash)
        values (?, 'SurvivorA', 101, 1, 'zombieBusinessMan', 900,
                '/private/log', 'ai-kill-current')
        """, at.minusMinutes(10));
    jdbcTemplate.update("""
        insert into t_player_position_transaction
        (occurred_at, player_name, player_entity_id, player_id, position_x, position_y, position_z,
         position_source_type, inference_method, movement_distance, movement_mode,
         source_event_hash, source_file)
        values (?, 'SurvivorA', 101, 1, 105, 30, 102, 'LP_COMMAND', 'direct', 12.5, 'ON_FOOT',
                'ai-position-current', '/private/log')
        """, at.minusMinutes(5));
    jdbcTemplate.update("""
        insert into t_world_event_transaction
        (occurred_at, event_type, player_id, actor_player_name, actor_player_entity_id,
         detail_text, source_file, source_log_hash, raw_line)
        values (?, 'WANDERING_HORDE', 1, 'SurvivorA', 101,
                'private-detail', '/private/log', 'ai-horde-current', 'secret-raw-line')
        """, at.minusMinutes(2));
    jdbcTemplate.update("""
        insert into t_world_time_observation
        (observed_at, game_day, game_hour, game_minute, source, source_hash, raw_response)
        values (?, 18, 9, 30, 'telnet:gettime', 'ai-world-time', 'private-raw-response')
        """, at.minusMinutes(1));
    jdbcTemplate.update("""
        insert into t_server_metric
        (occurred_at, fps, player_count, zombie_count, source_file, source_log_hash)
        values (?, 45.5, 1, 22, '/private/log', 'ai-server-metric')
        """, at.minusMinutes(1));

    WatchpointAiObservationService.AnalysisRequest request = service.buildRequest(at);

    assertThat(request.schemaVersion()).isEqualTo("watchpoint.observation.v1");
    assertThat(request.providerHint()).isEqualTo("AWS_BEDROCK_CONVERSE");
    assertThat(request.systemPrompt()).contains("あなたは「WATCHPOINT」です");
    assertThat(request.outputContract().maxBodyCharacters()).isEqualTo(100);
    assertThat(request.observation().currentWindow().minutes()).isEqualTo(30);
    assertThat(request.observation().currentTotals().joins()).isEqualTo(1);
    assertThat(request.observation().comparisonTotals().joins()).isEqualTo(1);
    assertThat(request.observation().currentTotals().kills()).isEqualTo(1);
    assertThat(request.observation().currentTotals().hordeEvents()).isEqualTo(1);
    assertThat(request.observation().survivors()).singleElement().satisfies(survivor -> {
      assertThat(survivor.name()).isEqualTo("SurvivorA");
      assertThat(survivor.visitedPois()).contains("旧式の平屋住宅");
    });
    assertThat(request.observation().events())
        .extracting(WatchpointAiObservationService.ObservedEvent::kind)
        .contains("KILL", "WANDERING_HORDE");

    String json = objectMapper.writeValueAsString(request);
    assertThat(json)
        .contains("evidenceKey", "SurvivorA")
        .doesNotContain("Steam_PRIVATE", "EOS_PRIVATE", "secret-raw-line",
            "private-raw-response", "/private/log", "private-detail");
  }
}
