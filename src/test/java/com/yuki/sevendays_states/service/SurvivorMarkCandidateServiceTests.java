package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuki.sevendays_states.config.SurvivorMarkProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:survivor_mark_candidates;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class SurvivorMarkCandidateServiceTests {

  @Autowired private SurvivorMarkCandidateService candidateService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clear() {
    jdbcTemplate.update("delete from t_timeline_post");
    jdbcTemplate.update("delete from t_entity_kill_transaction");
    jdbcTemplate.update("delete from t_sleeper_transaction");
    jdbcTemplate.update("delete from t_player_position_transaction");
    jdbcTemplate.update("delete from m_world_poi");
  }

  @Test
  void selectsOnlyAnEvidenceRichLocationFromTwoToFiveDaysAgo() {
    LocalDate publicationDate = LocalDate.of(2026, 8, 10);
    OffsetDateTime historical = OffsetDateTime.of(2026, 8, 7, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    OffsetDateTime currentDay = OffsetDateTime.of(2026, 8, 10, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    insertKill(historical, 120, 240, "zombieNurseRadiated", "old-kill");
    insertKill(historical.plusMinutes(2), 125, 245, "zombieScreamerRadiated", "old-kill-2");
    insertKill(currentDay, 900, 900, "zombieDemolisher", "current-kill");
    jdbcTemplate.update("""
        insert into m_world_poi(source_path, source_hash, world_name, game_name, poi_name, poi_type, category, x, y, z)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, "test", "mark-poi", "Navezgane", "save", "crack_a_book", "commercial", "store", 100, 0, 200);

    var selected = candidateService.select(publicationDate, properties());

    assertThat(selected).isPresent();
    assertThat(selected.orElseThrow())
        .satisfies(candidate -> {
          assertThat(candidate.x()).isBetween(100, 130);
          assertThat(candidate.z()).isBetween(230, 250);
          assertThat(candidate.kills()).isEqualTo(2);
          assertThat(candidate.zombieTypes()).contains("zombieNurseRadiated", "zombieScreamerRadiated");
          assertThat(candidate.poi()).isEqualTo("crack_a_book");
        });
  }

  private SurvivorMarkProperties properties() {
    return new SurvivorMarkProperties(14, 2, 5, 30);
  }

  private void insertKill(OffsetDateTime occurredAt, int x, int z, String type, String hash) {
    jdbcTemplate.update("""
        insert into t_entity_kill_transaction
          (occurred_at, player_name, player_entity_id, target_entity_type, target_entity_id,
           player_position_x, player_position_y, player_position_z, source_file, source_log_hash)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, occurredAt, "player", 1, type, 10, x, 60, z, "test.log", hash);
  }
}
