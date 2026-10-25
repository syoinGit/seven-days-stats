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
    "app.guild.enabled=true", "app.guild.visit-initial-delay=1h", "app.guild.daily-limit=2"
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
      {"name":"リネ","age":28,"gender":"女性","occupation":"道具職人","introduction":"旅の道具を直す職人。小さな店を持ちたい。",
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

  /** One Core Keeper session 70 days ago, 10:00-10:30 JST: exactly one memory (the 9-12 window). */
  private void source() {
    sourceAt(10);
  }

  private void sourceAt(int hour) {
    var at = LocalDate.now(GuildService.TOKYO).minusDays(70).atTime(hour, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    jdbc.update("INSERT INTO M_CK_PLAYER (steam_id,player_name,first_seen_at,last_seen_at) VALUES (?,'PRIVATE_PLAYER_SECRET',?,?)",
        "76561123456789" + hour, at, at);
    long id = jdbc.queryForObject("SELECT MAX(id) FROM M_CK_PLAYER", Long.class);
    jdbc.update("INSERT INTO T_CK_SESSION (ck_player_id,player_name,connected_at,disconnected_at) VALUES (?,'PRIVATE_PLAYER_SECRET',?,?)", id, at, at.plusMinutes(30));
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
    verify(bedrock, times(1)).generate(anyString(), argThat(prompt -> !prompt.contains("PRIVATE_PLAYER_SECRET") && !prompt.contains("765611")), eq(900), anyFloat(), any());
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).isEmpty();
    var character = guild.characters(0).getFirst();
    mvc.perform(get("/guild/characters/" + character.id())).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネ")))
        .andExpect(content().string(containsString("28歳・女性・道具職人")))
        .andExpect(content().string(containsString("Core Keeper・")))
        .andExpect(content().string(containsString("9時〜12時の記録から生まれました")))
        .andExpect(content().string(containsString("10:00 誰かが洞窟へ降りてきた")))
        .andExpect(content().string(containsString("10:30 誰かが地上へ戻った")))
        .andExpect(content().string(not(containsString("PRIVATE_PLAYER_SECRET"))))
        .andExpect(content().string(not(containsString("765611"))))
        .andExpect(content().string(not(containsString("source_key"))));
    mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("seat-0 moss")));
  }

  @Test void invalidOutputIsNotSavedAndRetryIsBounded() {
    source();
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenReturn("{\"name\":\"bad\"}");
    guild.generateToday();
    assertThat(guild.jobs().getFirst().status()).isEqualTo("FAILED");
    assertThat(guild.characters(0)).isEmpty();
    assertThat(guild.jobs().getFirst().message()).contains("AIの応答が形式に合いませんでした");
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenThrow(new IllegalStateException("PRIVATE_RESPONSE"));
    // daily-limit 2 in this test, plus 5 failures: 7 attempts in all, then no more calls.
    for (int i = 0; i < 8; i++) guild.generateToday();
    assertThat(guild.jobs().getFirst().attempts()).isEqualTo(7);
    assertThat(guild.jobs().getFirst().message()).doesNotContain("PRIVATE_RESPONSE");
    assertThat(guild.jobs().getFirst().message()).contains("Bedrockの呼び出しに失敗", "IllegalStateException");
    verify(bedrock, times(7)).generate(anyString(), anyString(), anyInt(), anyFloat(), any());
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

  @Test void memoriesComeFromPastDaysOnlyAndNeverNamePlayers() {
    var yesterday = LocalDate.now(GuildService.TOKYO).minusDays(1).atTime(21, 5).atZone(GuildService.TOKYO).toOffsetDateTime();
    jdbc.update("INSERT INTO T_PLAYER_JOIN_TRANSACTION (occurred_at,player_name,player_entity_id,platform_id,cross_platform_id,source_file,source_log_hash,created_at) VALUES (?,'PRIVATE_NAME',1,'PRIVATE_PLATFORM','PRIVATE_CROSS','guild-test','guild-test-hash',?)", yesterday, yesterday);
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).hasValueSatisfying(source -> {
      assertThat(source.game()).isEqualTo("7dtd");
      assertThat(source.label()).startsWith("7 Days to Die・").endsWith("21時〜24時の記録");
      assertThat(source.facts()).isEqualTo("21:05 誰かがこの地にやって来た");
      assertThat(source.key() + source.facts() + source.label()).doesNotContain("PRIVATE");
    });
    jdbc.update("UPDATE T_PLAYER_JOIN_TRANSACTION SET occurred_at = ? WHERE source_file='guild-test'", OffsetDateTime.now());
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO))).isEmpty();
  }

  @Test void tenRecordsAtMostAndInTimeOrder() {
    var day = LocalDate.now(GuildService.TOKYO).minusDays(3);
    for (int minute = 0; minute < 30; minute++) {
      var at = day.atTime(13, minute).atZone(GuildService.TOKYO).toOffsetDateTime();
      jdbc.update("INSERT INTO T_PLAYER_JOIN_TRANSACTION (occurred_at,player_name,player_entity_id,source_file,source_log_hash,created_at) VALUES (?,'PRIVATE_NAME',1,'guild-test',?,?)", at, "guild-test-" + minute, at);
    }
    var source = sources.choose(LocalDate.now(GuildService.TOKYO)).orElseThrow();
    List<String> lines = source.facts().lines().toList();
    assertThat(lines).hasSize(10);
    assertThat(lines).isSortedAccordingTo(java.util.Comparator.comparing(line -> java.time.LocalTime.parse(line.split(" ")[0], java.time.format.DateTimeFormatter.ofPattern("H:mm"))));
    assertThat(source.label()).endsWith("12時〜15時の記録");
  }

  @Test void administratorsAddUpToTheDailyLimitRedrawVisitorsAndDelete() throws Exception {
    sourceAt(1); sourceAt(10); sourceAt(19);
    var admin = user("admin").roles("ADMIN");
    mvc.perform(post("/guild/maintenance/generate").with(admin).with(csrf())).andExpect(status().is3xxRedirection());
    assertThat(guild.room(ZonedDateTime.now()).visits()).hasSize(1);
    mvc.perform(post("/guild/maintenance/generate").with(admin).with(csrf()));
    mvc.perform(post("/guild/maintenance/generate").with(admin).with(csrf()));
    assertThat(guild.characters(0)).hasSize(2);
    assertThat(guild.generateToday()).contains("上限の2人");
    assertThat(guild.generateScheduled()).contains("すでに住人を迎えています");
    // The second resident joins today's tavern only when the visitors are redrawn.
    assertThat(guild.room(ZonedDateTime.now()).visits()).hasSize(1);
    mvc.perform(post("/guild/maintenance/visits").with(admin).with(csrf())).andExpect(status().is3xxRedirection());
    assertThat(guild.room(ZonedDateTime.now()).visits()).hasSize(2);
    mvc.perform(get("/guild/maintenance").with(admin)).andExpect(status().isOk())
        .andExpect(content().string(containsString("今日迎えた住人: 2 / 2 人")))
        .andExpect(content().string(containsString("Core Keeper・")));

    long id = guild.characters(0).getFirst().id();
    mvc.perform(post("/guild/maintenance/characters/" + id + "/delete").with(user("member").roles("PLAYER")).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/guild/maintenance/characters/" + id + "/delete").with(admin).with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(guild.characters(0)).hasSize(1);
    assertThat(guild.room(ZonedDateTime.now()).visits()).hasSize(1);
    mvc.perform(get("/guild/characters/" + id)).andExpect(status().isNotFound());
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

  @Test void claudeStyleOutputIsAccepted() {
    // A code fence, a lead-in sentence, short numbers, line breaks and an extra key are all fine.
    String fenced = "人物を作りました。\n```json\n" + DRAFT.replace("小さな店", "3年後に小さな店").replace("節約家", "\\n節約家")
        .replace("\"appearance\"", "\"note\":\"x\",\"appearance\"") + "\n```";
    var draft = generator.validate(fenced);
    assertThat(draft.introduction()).contains("3年後に小さな店");
    assertThat(draft.personality()).contains("目がない").doesNotContain("\n");
    assertThat(draft.appearance()).isEqualTo("moss");
  }

  @Test void schemaRejectsUnsafeOrUnknownContent() {
    for (String bad : List.of(DRAFT.replace("moss", "unknown"), DRAFT.replace("女性", "王女"), DRAFT.replace("28", "200"),
        DRAFT.replace("\"age\":28,", ""), DRAFT.replace("リネ", "<script>"), DRAFT.replace("リネ", "https://secret"), DRAFT.replace("リネ", "76561123456"), DRAFT.replace("\"moss\"", "null"))) {
      assertThatThrownBy(() -> generator.validate(bad)).isInstanceOf(RuntimeException.class);
    }
  }
}
