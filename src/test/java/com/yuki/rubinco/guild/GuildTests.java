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
    "app.guild.enabled=true", "app.guild.visit-initial-delay=1h", "app.guild.daily-limit=2",
    // Women only and European names only, so drafts and profiles are predictable.
    "app.guild.female-ratio=1", "app.guild.lineage-weights.japanese=0", "app.guild.lineage-weights.north=0",
    "app.guild.lineage-weights.desert=0", "app.guild.lineage-weights.steppe=0"
})
@AutoConfigureMockMvc
class GuildTests {
  @Autowired GuildService guild;
  @Autowired GuildSourceService sources;
  @Autowired GuildCharacterGenerator generator;
  @Autowired GuildPeople people;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoBean BedrockTextClient bedrock;
  static final String DRAFT = """
      {"name":"リネ","age":28,"introduction":"旅の道具を直す職人。小さな店を持ちたい。",
       "personality":"慎重な話し方。修理は得意だが道に迷う。節約家なのに本には目がない。"}
      """;

  @BeforeEach void setup() {
    for (String table : List.of("T_GUILD_SOURCE", "T_GUILD_VISIT", "T_GUILD_DAY", "T_GUILD_JOB", "M_GUILD_CHARACTER", "T_CK_SESSION", "M_CK_PLAYER")) {
      jdbc.update("DELETE FROM " + table);
    }
    for (String table : List.of("T_PLAYER_JOIN_TRANSACTION", "T_WORLD_EVENT_TRANSACTION", "T_ENTITY_KILL_TRANSACTION")) {
      jdbc.update("DELETE FROM " + table + " WHERE source_file = 'guild-test'");
    }
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
    for (String path : List.of("/guild", "/guild/visitors", "/guild/board", "/guild/characters")) {
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
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).isEmpty();
    var character = guild.characters(0).getFirst();
    mvc.perform(get("/guild/characters/" + character.id())).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネ")))
        .andExpect(content().string(org.hamcrest.Matchers.anyOf(containsString("28歳・女性・冒険者"),
            containsString("28歳・女性・商人"), containsString("28歳・女性・猟師"))))
        .andExpect(content().string(containsString("ヨーロッパ系の名")))
        .andExpect(content().string(containsString("guild-people.svg?v=2#outfit-")))
        .andExpect(content().string(containsString("Core Keeper・")))
        .andExpect(content().string(containsString("9時〜12時の記録（探索）から生まれました")))
        .andExpect(content().string(containsString("10:00 誰かが洞窟へ降りてきた")))
        .andExpect(content().string(containsString("10:30 誰かが地上へ戻った")))
        .andExpect(content().string(not(containsString("PRIVATE_PLAYER_SECRET"))))
        .andExpect(content().string(not(containsString("765611"))))
        .andExpect(content().string(not(containsString("source_key"))));
    mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("seat-0 ")))
        .andExpect(content().string(containsString("#outfit-")))
        .andExpect(content().string(containsString("class=\"guild-link active\"")))
        .andExpect(content().string(not(containsString("<dialog id=\"guild-window\" class=\"guild-window\" open"))));
  }

  @Test void windowsOpenOverTheTavernWithTheirTab() throws Exception {
    source();
    guild.generateToday();
    guild.refreshVisits();
    var character = guild.characters(0).getFirst();
    mvc.perform(get("/guild/visitors")).andExpect(status().isOk())
        .andExpect(content().string(containsString("guild-room")))
        .andExpect(content().string(containsString("open=\"open\"")))
        .andExpect(content().string(containsString("今日の来店者")))
        .andExpect(content().string(containsString("/guild/characters/" + character.id())))
        .andExpect(content().string(containsString("href=\"/guild/visitors\" aria-current=\"page\"")));
    mvc.perform(get("/guild/board")).andExpect(status().isOk())
        .andExpect(content().string(containsString("いちばん新しい住人")))
        .andExpect(content().string(containsString("リネ")));
    mvc.perform(get("/guild/characters/" + character.id())).andExpect(status().isOk())
        .andExpect(content().string(containsString("<title>リネ | Rubinco</title>")))
        .andExpect(content().string(containsString("href=\"/guild/characters\" aria-current=\"page\"")));
    mvc.perform(get("/guild")).andExpect(status().isOk())
        .andExpect(content().string(not(containsString("/guild/maintenance\"><svg"))));
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
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).isEmpty();
    worldEvent(yesterday.plusMinutes(1), "AIR_DROP", "air");
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).hasValueSatisfying(source -> {
      assertThat(source.game()).isEqualTo("7dtd");
      assertThat(source.category()).isEqualTo("exploration");
      assertThat(source.label()).startsWith("7 Days to Die・").endsWith("21時〜24時の記録（探索）");
      assertThat(source.facts()).isEqualTo("21:05 誰かがこの地にやって来た\n21:06 補給物資が空から降ってきた");
      assertThat(source.key() + source.facts() + source.label()).doesNotContain("PRIVATE");
    });
    jdbc.update("UPDATE T_WORLD_EVENT_TRANSACTION SET occurred_at = ? WHERE source_file='guild-test'", OffsetDateTime.now());
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).isEmpty();
  }

  @Test void tenRecordsAtMostAndInTimeOrder() {
    var day = LocalDate.now(GuildService.TOKYO).minusDays(3);
    for (int minute = 0; minute < 30; minute++) {
      worldEvent(day.atTime(13, minute).atZone(GuildService.TOKYO).toOffsetDateTime(), "AIR_DROP", "air-" + minute);
    }
    var source = sources.choose(LocalDate.now(GuildService.TOKYO)).source().orElseThrow();
    List<String> lines = source.facts().lines().toList();
    assertThat(lines).hasSize(10);
    assertThat(lines).isSortedAccordingTo(java.util.Comparator.comparing(line -> java.time.LocalTime.parse(line.split(" ")[0], java.time.format.DateTimeFormatter.ofPattern("H:mm"))));
    assertThat(source.label()).endsWith("12時〜15時の記録（探索）");
  }

  private void worldEvent(OffsetDateTime at, String type, String hash) {
    jdbc.update("INSERT INTO T_WORLD_EVENT_TRANSACTION (occurred_at,event_type,actor_player_name,source_file,source_log_hash,raw_line) "
        + "VALUES (?,?,'PRIVATE_NAME','guild-test',?,'PRIVATE_RAW')", at, type, "guild-test-" + hash);
  }

  private void kill(OffsetDateTime at, String hash) {
    jdbc.update("INSERT INTO T_ENTITY_KILL_TRANSACTION (occurred_at,player_name,player_entity_id,target_entity_type,target_entity_id,source_file,source_log_hash) "
        + "VALUES (?,'PRIVATE_NAME',1,'zombieBoe',2,'guild-test',?)", at, "guild-test-" + hash);
  }

  @Test void bloodMoonFightsAreOneCategoryAndOtherFightsAnother() {
    var moon = LocalDate.now(GuildService.TOKYO).minusDays(5).atTime(22, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    var noon = LocalDate.now(GuildService.TOKYO).minusDays(4).atTime(13, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    worldEvent(moon, "BLOOD_MOON", "moon");
    kill(moon.plusMinutes(5), "moon-kill");
    kill(noon, "noon-kill");
    assertThat(sources.inventory(LocalDate.now(GuildService.TOKYO))).containsExactly(
        new GuildSourceService.Inventory("7dtd", "bloodmoon", 1), new GuildSourceService.Inventory("7dtd", "combat", 1));
  }

  @Test void aCategoryAtItsCapIsLeftOutUntilOthersArrive() throws Exception {
    // Three recent residents (the cap) were born from exploration memories.
    for (int i = 1; i <= 3; i++) {
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance) VALUES (?,'corekeeper',?,'紹介','出自','性格','moss')",
          LocalDate.now(GuildService.TOKYO).minusDays(i), "テスト" + "アイウ".charAt(i - 1));
      long id = jdbc.queryForObject("SELECT MAX(id) FROM M_GUILD_CHARACTER", Long.class);
      jdbc.update("INSERT INTO T_GUILD_SOURCE (character_id,source_key,facts,memory_label,category) VALUES (?,?,'x','x','exploration')", id, "memory:test:" + i);
    }
    source();
    var choice = sources.choose(LocalDate.now(GuildService.TOKYO));
    assertThat(choice.source()).isEmpty();
    assertThat(choice.skipped()).contains("探索").contains("偏り");
    assertThat(guild.generateToday()).contains("偏り");
    verifyNoInteractions(bedrock);
    mvc.perform(get("/guild/maintenance").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
        .andExpect(content().string(containsString("直近の住人（新しい順）: 探索、探索、探索")))
        .andExpect(content().string(containsString("<td>Core Keeper</td><td>探索</td><td>1</td>")));
    // A fight from another game is still welcome.
    kill(LocalDate.now(GuildService.TOKYO).minusDays(2).atTime(13, 0).atZone(GuildService.TOKYO).toOffsetDateTime(), "other");
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).hasValueSatisfying(source ->
        assertThat(source.category()).isEqualTo("combat"));
  }

  @Test void codeChoosesOccupationGenderAndLookFromTheMemory() {
    for (int i = 0; i < 40; i++) {
      var plan = people.plan("bloodmoon");
      assertThat(plan.gender()).isEqualTo("女性");
      assertThat(plan.occupation()).isIn("夜警", "騎士", "冒険者", "聖職者");
      assertThat(plan.lineage()).isEqualTo(GuildPeople.Lineage.EUROPEAN);
      // A woman knight wears the women's cut of plate; outfits without their own legs get a skirt.
      if (plan.occupation().equals("騎士")) assertThat(plan.look().outfit()).isEqualTo("knight_f");
      assertThat(plan.look().layers()).startsWith("body").contains("outfit-" + plan.look().outfit());
      assertThat(plan.look().style()).matches("(--[a-z0-9]+:#[0-9a-f]{6};?)+");
    }
    assertThat(people.plan("fishing").occupation()).isIn("漁師", "料理人", "農家");
    assertThat(GuildPeople.Look.of("knight", "short", "#000000", "#ffffff", "#123456", "#654321", "男性").layers())
        .containsExactly("body", "outfit-knight", "hair-short", "hat-knight");
    assertThat(GuildPeople.Look.of("cook", "bob", "#000000", "#ffffff", "#123456", "#654321", "女性").layers())
        .containsExactly("body", "legs-skirt", "outfit-cook", "hair-bob", "hat-cook");
    assertThat(GuildPeople.Look.of("cook", "bob", "red;background:url(x)", "#ffffff", "#123456", "#654321", "女性")).isNull();
    assertThat(GuildPeople.Lineage.JAPANESE.fits("灯子")).isTrue();
    assertThat(GuildPeople.Lineage.JAPANESE.fits("リネ")).isFalse();
    assertThat(GuildPeople.Lineage.EUROPEAN.fits("リネ")).isTrue();
    assertThat(GuildPeople.Lineage.EUROPEAN.fits("Rine")).isFalse();
  }

  @Test void aNameAlreadyTakenOrAPlayersNameIsNotSaved() {
    sourceAt(1); sourceAt(10);
    guild.generateToday();
    assertThat(guild.generateToday()).contains("名前と重なりました");
    assertThat(guild.characters(0)).hasSize(1);
    jdbc.update("DELETE FROM T_GUILD_JOB");
    jdbc.update("INSERT INTO M_PLAYER (player_key,platform,user_id,player_name) VALUES ('guild-test','test','guild-test','ミナトリネ')");
    try {
      when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenReturn(DRAFT.replace("リネ", "ミナト"));
      assertThat(guild.generateToday()).contains("名前と重なりました");
    } finally {
      jdbc.update("DELETE FROM M_PLAYER WHERE player_name = 'ミナトリネ'");
    }
  }

  @Test void administratorsAddUpToTheDailyLimitRedrawVisitorsAndDelete() throws Exception {
    sourceAt(1); sourceAt(10); sourceAt(19);
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any()))
        .thenReturn(DRAFT, DRAFT.replace("リネ", "ロアン"), DRAFT.replace("リネ", "エルダ"));
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
    for (String path : List.of("/guild", "/guild/characters", "/guild/visitors", "/guild/board")) {
      var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
      String html = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replace("<main class=", "<p style=\"text-align:center;color:#f0d8c2\">表示確認用サンプル・実プレイ由来ではありません</p><main class=");
      java.nio.file.Files.writeString(root.resolve(path.equals("/guild") ? "index.html" : path.substring(7) + ".html"), html);
    }
    for (var person : guild.characters(0)) {
      var response = mvc.perform(get("/guild/characters/" + person.id())).andExpect(status().isOk()).andReturn().getResponse();
      java.nio.file.Files.writeString(root.resolve("guild/characters/" + person.id()), response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test void claudeStyleOutputIsAccepted() {
    // A code fence, a lead-in sentence, short numbers, line breaks and an extra key are all fine.
    String fenced = "人物を作りました。\n```json\n" + DRAFT.replace("小さな店", "3年後に小さな店").replace("節約家", "\\n節約家")
        .replace("\"personality\"", "\"note\":\"x\",\"personality\"") + "\n```";
    var draft = generator.validate(fenced, GuildPeople.Lineage.EUROPEAN);
    assertThat(draft.introduction()).contains("3年後に小さな店");
    assertThat(draft.personality()).contains("目がない").doesNotContain("\n");
    assertThat(draft.age()).isEqualTo(28);
  }

  @Test void schemaRejectsUnsafeOrUnknownContent() {
    for (String bad : List.of(DRAFT.replace("28", "200"), DRAFT.replace("\"age\":28,", ""), DRAFT.replace("リネ", "<script>"),
        DRAFT.replace("リネ", "https://secret"), DRAFT.replace("リネ", "76561123456"), DRAFT.replace("リネ", "凛"),
        DRAFT.replace("リネ", "リネリネリネリネリ"), DRAFT.replace("\"リネ\"", "null"))) {
      assertThatThrownBy(() -> generator.validate(bad, GuildPeople.Lineage.EUROPEAN)).isInstanceOf(RuntimeException.class);
    }
  }
}
