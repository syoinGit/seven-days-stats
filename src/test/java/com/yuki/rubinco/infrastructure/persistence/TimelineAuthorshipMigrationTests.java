package com.yuki.rubinco.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TimelineAuthorshipMigrationTests {

  @Test
  void upgradeRecoversOnlyRecordedAuthorsWithoutRestoringDeletedPosts() {
    var dataSource = new DriverManagerDataSource(
        System.getProperty("migration.test.url",
            "jdbc:h2:mem:authorship_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
        System.getProperty("migration.test.user", "sa"), System.getProperty("migration.test.password", ""));
    Flyway.configure().dataSource(dataSource).target("39").load().migrate();
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
        INSERT INTO M_PLAYER(id, player_key, platform, user_id, player_name)
        VALUES (100, 'EOS:author', 'EOS', 'author', 'Player')
        """);
    jdbc.update("""
        INSERT INTO M_WEB_ACCOUNT(id, login_id, password_hash, role, enabled, created_at, updated_at)
        VALUES (10, 'original', 'hash', 'PLAYER', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
               (20, 'replacement', 'hash', 'PLAYER', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """);
    jdbc.update("UPDATE M_WEB_ACCOUNT SET player_id = 100 WHERE id = 20");
    jdbc.update("""
        INSERT INTO T_PLAYER_POST(id, account_id, player_id, body, created_at)
        VALUES (50, 10, 100, 'legacy', CURRENT_TIMESTAMP),
               (51, 10, 100, 'previously deleted from feed', CURRENT_TIMESTAMP)
        """);
    insertPost(jdbc, 1, 50L, "PLAYER_POST:50");
    insertPost(jdbc, 2, null, "PLAYER_POST:10:2026-10-16T00:00:00Z");
    insertPost(jdbc, 3, null, "PLAYER_POST:999:2026-10-16T00:00:00Z");
    insertPost(jdbc, 4, null, "unattributable-legacy-post");
    insertPost(jdbc, 5, null, "PLAYERXPOST:10:2026-10-16T00:00:00Z");

    Flyway.configure().dataSource(dataSource).load().migrate();

    assertThat(jdbc.queryForList("SELECT author_account_id FROM T_TIMELINE_POST ORDER BY timeline_post_id", Long.class))
        .containsExactly(10L, 10L, null, null, null);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_TIMELINE_POST", Integer.class)).isEqualTo(5);
    // Legacy tables have their own account FK; remove those fixtures before testing the new FK.
    jdbc.update("DELETE FROM T_PLAYER_POST");
    jdbc.update("DELETE FROM M_WEB_ACCOUNT WHERE id = 10");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_TIMELINE_POST WHERE author_account_id IS NOT NULL", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_TIMELINE_POST", Integer.class)).isEqualTo(5);
  }

  private void insertPost(JdbcTemplate jdbc, long id, Long sourceId, String hash) {
    jdbc.update("""
        INSERT INTO T_TIMELINE_POST(timeline_post_id, published_at, post_type, actor_type,
          actor_player_id, actor_name, message, source_type, source_id, source_hash, priority, created_at)
        VALUES (?, CURRENT_TIMESTAMP, 'PLAYER_MESSAGE', 'PLAYER', 100, 'Player', 'post',
          'PLAYER_POST', ?, ?, 100, CURRENT_TIMESTAMP)
        """, id, sourceId, hash);
  }
}
