package com.yuki.rubinco;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rubinco;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class RubincoApplicationTests {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private Flyway flyway;

  @Test
  void contextLoads() {
  }

  @Test
  void flywayCreatesCurrentTables() {
    assertThat(tableExists("T_IMPORT_RUN")).isTrue();
    assertThat(tableExists("M_SERVER_CONFIG_SETTING")).isTrue();
    assertThat(tableExists("M_GAME_CONFIG_ELEMENT")).isTrue();
    assertThat(tableExists("M_JAPANESE_TRANSLATION")).isTrue();
    assertThat(tableExists("M_GAME_ENTITY")).isTrue();
    assertThat(tableExists("M_BLOCK")).isTrue();
    assertThat(tableExists("M_ITEM")).isTrue();
    assertThat(tableExists("M_VEHICLE")).isTrue();
    assertThat(tableExists("M_WORLD")).isTrue();
    assertThat(tableExists("M_GAME_SAVE")).isTrue();
    assertThat(tableExists("M_WORLD_POI")).isTrue();
    assertThat(tableExists("M_WORLD_SPAWN_POINT")).isTrue();
    assertThat(tableExists("M_PLAYER")).isTrue();
    assertThat(tableExists("T_PLAYER_STATE_SNAPSHOT")).isTrue();
    assertThat(tableExists("T_PLAYER_MARKER_SNAPSHOT")).isTrue();
    assertThat(tableExists("M_WEB_ACCOUNT")).isTrue();
    assertThat(tableExists("M_GAME_PORTAL_STATE")).isTrue();
    assertThat(columnExists("T_TIMELINE_POST", "GAME_ID")).isTrue();
    assertThat(columnExists("T_TIMELINE_POST", "EVENT_ID")).isTrue();
    assertThat(columnExists("T_TIMELINE_POST", "CREATOR_SUB")).isTrue();
    assertThat(tableExists("T_PLAYER_POST")).isTrue();
    assertThat(tableExists("T_PLAYER_POST_LIKE")).isTrue();
    assertThat(tableExists("M_SOURCE_FILE")).isFalse();
    assertThat(tableExists("M_SERVER_COMMAND_PERMISSION")).isFalse();
    assertThat(tableExists("T_SAVE_FILE_SNAPSHOT")).isFalse();
  }

  @Test
  void repairsKnownProductionV15ChecksumBeforeValidation() {
    jdbcTemplate.update(
        "update \"flyway_schema_history\" set \"checksum\" = ?, \"description\" = ? "
            + "where \"version\" = '15'",
        -1037278684, "backfill xp player from recent kills");

    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "select \"checksum\" from \"flyway_schema_history\" where \"version\" = '15'",
        Integer.class))
        .isEqualTo(-853613223);
    assertThat(jdbcTemplate.queryForObject(
        "select \"description\" from \"flyway_schema_history\" where \"version\" = '15'",
        String.class))
        .isEqualTo("add dashboard query indexes");
  }

  private boolean tableExists(String tableName) {
    Long count = jdbcTemplate.queryForObject("""
        select count(*)
        from information_schema.tables
        where upper(table_name) = ?
        """, Long.class, tableName);
    return count != null && count > 0;
  }

  private boolean columnExists(String tableName, String columnName) {
    Long count = jdbcTemplate.queryForObject("""
        select count(*)
        from information_schema.columns
        where upper(table_name) = ? and upper(column_name) = ?
        """, Long.class, tableName, columnName);
    return count != null && count > 0;
  }

}
