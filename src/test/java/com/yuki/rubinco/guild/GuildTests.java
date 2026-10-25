package com.yuki.rubinco.guild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.yuki.rubinco.watchpoint.ai.BedrockTextClient;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:guild;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true", "app.sevendays.import.startup-enabled=false",
    "app.guild.enabled=true", "app.guild.visit-initial-delay=1h"
})
@AutoConfigureMockMvc
class GuildTests {
  @Autowired GuildService guild;
  @Autowired GuildSourceService sources;
  @Autowired GuildCharacterGenerator generator;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoBean BedrockTextClient bedrock;
  static final String DRAFT = """
      {"name":"リネ","introduction":"旅の道具を直す職人。小さな店を持ちたい。",
       "personality":"慎重な話し方。修理は得意だが道に迷う。節約家なのに本には目がない。","appearance":"moss"}
      """;

  @BeforeEach void setup() {
    for (String table : List.of("T_GUILD_SOURCE", "T_GUILD_VISIT", "T_GUILD_DAY", "T_GUILD_JOB", "M_GUILD_CHARACTER", "T_CK_SESSION", "M_CK_PLAYER")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("DELETE FROM T_PLAYER_JOIN_TRANSACTION WHERE source_file = 'guild-test'");
    jdbc.update("DELETE FROM T_WORLD_SNAPSHOT WHERE parser_version = 'guild-test'");
    reset(bedrock);
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenReturn(DRAFT);
  }

  private void source() {
    var at = OffsetDateTime.now().minusDays(70);
    jdbc.update("INSERT INTO M_CK_PLAYER (steam_id,player_name,first_seen_at,last_seen_at) VALUES ('76561123456789012','PRIVATE_PLAYER_SECRET',?,?)", at, at);
    long id = jdbc.queryForObject("SELECT id FROM M_CK_PLAYER", Long.class);
    jdbc.update("INSERT INTO T_CK_SESSION (ck_player_id,player_name,connected_at,disconnected_at) VALUES (?,'PRIVATE_PLAYER_SECRET',?,?)", id, at, at.plusHours(1));
  }

  @Test void publicViewsAreReadOnlyAndAdminWritesNeedRoleAndCsrf() throws Exception {
    for (String path : List.of("/guild", "/guild/characters")) {
      mvc.perform(get(path)).andExpect(status().isOk());
    }
    mvc.perform(get("/guild/maintenance")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/guild/maintenance").with(user("member").roles("PLAYER"))).andExpect(status().isForbidden());
    mvc.perform(post("/guild/maintenance/generate").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    mvc.perform(post("/guild/maintenance/generate").with(user("member").roles("PLAYER")).with(csrf())).andExpect(status().isForbidden());
    mvc.perform(get("/guild/maintenance").with(user("admin").roles("ADMIN", "VIEWER"))).andExpect(status().isForbidden());
    mvc.perform(get("/guild/maintenance").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    verifyNoInteractions(bedrock);
    assertThat(guild.jobs()).isEmpty();
    mvc.perform(get("/guild/characters/999999")).andExpect(status().isNotFound());
  }

  @Test void concurrentBirthsUseOneSourceAndNeverExposeIt() throws Exception {
    source();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results = executor.invokeAll(List.of(guild::generateToday, guild::generateToday));
      for (var result : results) assertThat(result.get()).isNotNull();
    }
    assertThat(guild.characters(0)).hasSize(1);
    verify(bedrock, times(1)).generate(anyString(), argThat(prompt -> !prompt.contains("PRIVATE_PLAYER_SECRET") && !prompt.contains("765611")), eq(850), anyFloat(), any());
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).isEmpty();
    var character = guild.characters(0).getFirst();
    mvc.perform(get("/guild/characters/" + character.id())).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネ")))
        .andExpect(content().string(not(containsString("PRIVATE_PLAYER_SECRET"))))
        .andExpect(content().string(not(containsString("source_key"))));
    mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("seat-0 moss")));
  }

  @Test void invalidOutputIsNotSavedAndRetryIsBounded() {
    source();
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenReturn("{\"name\":\"bad\"}");
    guild.generateToday();
    assertThat(guild.jobs().getFirst().status()).isEqualTo("FAILED");
    assertThat(guild.characters(0)).isEmpty();
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenThrow(new IllegalStateException("PRIVATE_RESPONSE"));
    guild.generateToday(); guild.generateToday(); guild.generateToday();
    assertThat(guild.jobs().getFirst().attempts()).isEqualTo(3);
    assertThat(guild.jobs().getFirst().message()).doesNotContain("PRIVATE_RESPONSE");
    verify(bedrock, times(3)).generate(anyString(), anyString(), anyInt(), anyFloat(), any());
  }

  @Test void failedGenerationCanRecoverWithoutLosingExistingPeople() {
    source();
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenReturn("invalid").thenReturn(DRAFT);
    guild.generateToday(); guild.generateToday();
    assertThat(guild.characters(0)).hasSize(1);
    assertThat(guild.jobs().getFirst().status()).isEqualTo("SUCCEEDED");
    assertThat(guild.jobs().getFirst().attempts()).isEqualTo(2);
  }

  @Test void noSourceMeansNoInventedCharacter() {
    guild.generateToday();
    assertThat(guild.characters(0)).isEmpty();
    assertThat(guild.jobs().getFirst().status()).isEqualTo("SKIPPED");
    verifyNoInteractions(bedrock);
  }

  @Test void rosterIsStableAcrossReadsAndJstPhasesAndDeadRemainReadable() throws Exception {
    source(); guild.generateToday();
    var day = LocalDate.now(GuildService.TOKYO);
    var morning = guild.room(day.atTime(9, 0).atZone(GuildService.TOKYO));
    var evening = guild.room(day.atTime(21, 0).atZone(GuildService.TOKYO));
    assertThat(morning.phase()).isEqualTo("morning");
    assertThat(evening.phase()).isEqualTo("evening");
    assertThat(morning.visits().getFirst().character()).isEqualTo(evening.visits().getFirst().character());
    assertThat(morning.visits().getFirst().line()).isNotEqualTo(evening.visits().getFirst().line());
    assertThat(guild.room(day.atTime(21, 0).atZone(GuildService.TOKYO))).isEqualTo(evening);
    assertThat(guild.room(ZonedDateTime.parse(day + "T00:00:00Z")).phase()).isEqualTo("morning");
    guild.prepareToday();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_VISIT", Integer.class)).isOne();
    long id = morning.visits().getFirst().character().id();
    jdbc.update("UPDATE M_GUILD_CHARACTER SET status='DEAD' WHERE id=?", id);
    assertThat(guild.room(ZonedDateTime.now()).visits()).isEmpty();
    mvc.perform(get("/guild/characters/" + id)).andExpect(status().isOk()).andExpect(content().string(containsString("亡くなりました")));
  }

  @Test void unlinkedLegacyPlayersAndWorldSnapshotsAreEligibleButTodayIsNot() {
    var yesterday = LocalDate.now(GuildService.TOKYO).minusDays(1).atStartOfDay(GuildService.TOKYO).toOffsetDateTime();
    jdbc.update("INSERT INTO T_PLAYER_JOIN_TRANSACTION (occurred_at,player_name,player_entity_id,platform_id,cross_platform_id,source_file,source_log_hash,created_at) VALUES (?,'PRIVATE_NAME',1,'PRIVATE_PLATFORM','PRIVATE_CROSS','guild-test','guild-test-hash',?)", yesterday, yesterday);
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).hasValueSatisfying(source -> {
      assertThat(source.game()).isEqualTo("7dtd");
      assertThat(source.facts()).doesNotContain("PRIVATE");
    });
    jdbc.update("UPDATE T_PLAYER_JOIN_TRANSACTION SET occurred_at = ? WHERE source_file='guild-test'", OffsetDateTime.now());
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).isEmpty();
    Long world = jdbc.queryForObject("SELECT game_world_id FROM M_GAME_WORLD WHERE game_id='terraria'", Long.class);
    jdbc.update("INSERT INTO T_WORLD_SNAPSHOT (game_world_id,source_sha256,parser_version,captured_at,parsed_at,validation_status,extracted_json) VALUES (?,'guild-world-hash','guild-test',?,?,'VALID','PRIVATE_RAW_WORLD')", world, yesterday, yesterday);
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).hasValueSatisfying(source -> {
      assertThat(source.game()).isEqualTo("terraria");
      assertThat(source.facts()).contains("世界由来").doesNotContain("PRIVATE");
    });
    jdbc.update("UPDATE T_WORLD_SNAPSHOT SET validation_status='INVALID' WHERE parser_version='guild-test'");
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).isEmpty();
  }

  @Test void savedRosterDoesNotChangeAfterBirthAndRenderPreviewFixtures() throws Exception {
    // Explicitly synthetic preview residents, never inserted in a real application database.
    for (int i = 1; i <= 5; i++) {
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance) VALUES (?,'terraria',?,'小さな街で暮らす職人。今日は道具の手入れをしています。','表示確認用の架空データです。','静かな話し方。手仕事が好きで、方向音痴。',?)",
          LocalDate.now(GuildService.TOKYO).minusDays(i), List.of("ロアン", "ミナ", "エルド", "トト", "シエラ").get(i-1), List.of("ember", "moss", "violet").get(i % 3));
    }
    guild.prepareToday();
    var before = guild.room(ZonedDateTime.now());
    source(); guild.generateToday();
    assertThat(guild.room(ZonedDateTime.now())).isEqualTo(before);
    assertThat(before.visits()).hasSize(5);
    var root = java.nio.file.Path.of("target/guild-preview");
    java.nio.file.Files.createDirectories(root.resolve("guild/characters"));
    for (String path : List.of("/guild", "/guild/characters")) {
      var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
      String html = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replace("<main class=", "<p style=\"text-align:center;color:#f0d8c2\">表示確認用サンプル・実プレイ由来ではありません</p><main class=");
      java.nio.file.Files.writeString(root.resolve(path.equals("/guild") ? "index.html" : "characters.html"), html);
    }
    for (var person : guild.characters(0)) {
      var response = mvc.perform(get("/guild/characters/" + person.id())).andExpect(status().isOk()).andReturn().getResponse();
      java.nio.file.Files.writeString(root.resolve("guild/characters/" + person.id()), response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test void schemaRejectsUnsafeOrUnknownContent() {
    for (String bad : List.of(DRAFT.replace("moss", "unknown"), DRAFT.replace("リネ", "<script>"), DRAFT.replace("リネ", "https://secret"), DRAFT.replace("リネ", "76561123456"), DRAFT.replace("\"moss\"", "null"))) {
      assertThatThrownBy(() -> generator.validate(bad)).isInstanceOf(RuntimeException.class);
    }
  }
}
