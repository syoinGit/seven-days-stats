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
import org.springframework.test.util.ReflectionTestUtils;
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
  @Autowired GuildPeople people;
  @Autowired GuildInteractionService interactions;
  @Autowired GuildInteractionWriter writer;
  @Autowired GuildChronicle chronicle;
  @Autowired GuildDossier dossier;
  @Autowired GuildWorld world;
  @Autowired GuildProperties properties;
  @Autowired GuildDungeon dungeon;
  @Autowired GuildVoice voices;
  @Autowired GuildTalks talks;
  @Autowired GuildThoughts thoughts;
  @Autowired GuildRumors rumors;
  @Autowired GuildMenus menus;
  @Autowired GuildWerewolf werewolf;
  @Autowired GuildNews news;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.yuki.rubinco.account.repository.M_WebAccountRepository accounts;
  @Autowired MockMvc mvc;
  @MockitoBean BedrockTextClient bedrock;
  static final String DRAFT = """
      {"reading":"出入りの多い短い一日から、身軽に動き回る暮らしが見えた。","gender":"女性","occupation":"冒険者",
       "lineage":"ヨーロッパ系","village":"グランフォード","faction":"無所属","faith":"無信仰","name":"リネ","age":28,"introduction":"旅の道具を直す職人。小さな店を持ちたい。",
       "personality":"慎重な話し方。修理は得意だが道に迷う。節約家なのに本には目がない。"}
      """;

  /** Every choice open, as for a town with no residents yet. */
  static final GuildPeople.Options OPEN = new GuildPeople.Options(GuildPeople.GENDERS,
      GuildPeople.OUTFITS.keySet().stream().sorted().toList(), List.of(GuildPeople.Lineage.values()),
      List.of("grantford", "elmwood", "tsukikage"), List.of("lord_house", "silver_knights", "verdant_wardens", "tsukikage_samurai", "none"),
      List.of("dawnlight", "spirit", "none"), List.of());

  @BeforeEach void setup() {
    talks.forgetTries();
    for (String table : List.of("T_GUILD_THOUGHT", "T_GUILD_TALK_CHOICE", "T_GUILD_TALK", "T_GUILD_RELATION_EVENT", "T_GUILD_EXPEDITION_STAGE", "T_GUILD_EXPEDITION_MEMBER", "T_GUILD_EXPEDITION", "T_GUILD_AFFILIATION_HISTORY", "T_GUILD_THREAD", "T_GUILD_INTERACTION_PARTICIPANT", "T_GUILD_INTERACTION",
        "T_GUILD_RELATION", "T_GUILD_BOND", "T_GUILD_HOURLY_JOB", "T_GUILD_SOURCE", "T_GUILD_VISIT", "T_GUILD_DAY", "T_GUILD_JOB", "M_GUILD_CHARACTER", "T_CK_SESSION", "M_CK_PLAYER")) {
      jdbc.update("DELETE FROM " + table);
    }
    for (String table : List.of("T_PLAYER_JOIN_TRANSACTION", "T_WORLD_EVENT_TRANSACTION", "T_ENTITY_KILL_TRANSACTION", "T_PLAYER_POSITION_TRANSACTION")) {
      jdbc.update("DELETE FROM " + table + " WHERE source_file = 'guild-test'");
    }
    String snapshots = "(SELECT world_snapshot_id FROM T_WORLD_SNAPSHOT WHERE parser_version = 'guild-test')";
    jdbc.update("DELETE FROM M_WORLD_POI WHERE source_hash LIKE 'guild-test%'");
    jdbc.update("DELETE FROM T_PROGRESS_EVENT WHERE world_snapshot_id IN " + snapshots);
    jdbc.update("DELETE FROM T_CK_CHEST_CHANGE WHERE world_snapshot_id IN " + snapshots);
    jdbc.update("DELETE FROM T_PROGRESS_STATE WHERE world_snapshot_id IN " + snapshots);
    jdbc.update("DELETE FROM T_CK_CONTAINER WHERE world_snapshot_id IN " + snapshots);
    jdbc.update("DELETE FROM T_WORLD_SNAPSHOT WHERE parser_version = 'guild-test'");
    reset(bedrock);
    when(bedrock.generate(anyString(), anyString(), anyInt(), any())).thenReturn(DRAFT);
    when(bedrock.generate(anyString(), anyString(), eq(GuildInteractionWriter.MAX_TOKENS), any())).thenReturn(SCENE);
  }

  static final String SCENE = """
      {"narration":"夕方の酒場。窓の外では雨が降っている。","lines":[{"speaker":"リネ","text":"今日は冷えるね。"},
       {"speaker":"ロアン","text":"火のそばに座ろう。"}]}
      """;
  static final String TALK = """
      {"opening":"この店の煮込み、今夜は少し塩が強いと思いませんか。わたしは好きなんですけど、あなたはどうです？",
       "choices":[{"label":"たしかに少し濃いですね","reply":"ふふ、やっぱり。でも寒い夜にはこれくらいがちょうどいいんです。おかわり、頼んじゃいましょうか。"},
                  {"label":"何が入っているんですか","reply":"根菜と干し肉、それに森で採れる香草です。香草は店主の秘密らしくて、わたしも名前を知らないんですよ。"},
                  {"label":"あなたの舌が鈍っているのでは","reply":"まあ、ひどい。これでも料理の味には自信があるんです。明日、わたしの作ったものを食べてから言ってくださいね。"}]}
      """;

  /** Today at 20:00 JST, one of the daily slots: the slot every interaction test runs in. */
  static final ZonedDateTime HOUR = LocalDate.now(GuildService.TOKYO).atTime(20, 0).atZone(GuildService.TOKYO);

  /** A resident sitting in today's tavern, inserted directly so interactions don't need births. */
  private long resident(String name, int age, String status, int seat) {
    jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance,status,age,gender,occupation) "
        + "VALUES (?,'core-keeper',?,'旅の者。','記録から。','穏やか。','moss',?,?,'女性','冒険者')",
        LocalDate.now(GuildService.TOKYO).minusDays(100 + seat), name, status, age);
    long id = jdbc.queryForObject("SELECT id FROM M_GUILD_CHARACTER WHERE name = ?", Long.class, name);
    if (count("SELECT COUNT(*) FROM T_GUILD_DAY WHERE visit_date = ?", HOUR.toLocalDate()) == 0) {
      jdbc.update("INSERT INTO T_GUILD_DAY (visit_date) VALUES (?)", HOUR.toLocalDate());
    }
    jdbc.update("INSERT INTO T_GUILD_VISIT (visit_date,character_id,seat,morning,afternoon,evening) VALUES (?,?,?,'おはよう','こんにちは','こんばんは')",
        HOUR.toLocalDate(), id, seat);
    return id;
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  /**
   * One memory: a player defeated "zombieGuild10" beside an old house 70 days ago at 10:00 JST. A Core Keeper
   * player and session exist too, only to prove neither their name nor id ever leaves.
   */
  private void source() {
    sourceAt(10);
  }

  /** Each hour is a different enemy, so a separate memory. */
  private void sourceAt(int hour) {
    var at = LocalDate.now(GuildService.TOKYO).minusDays(70).atTime(hour, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    jdbc.update("INSERT INTO M_CK_PLAYER (steam_id,player_name,first_seen_at,last_seen_at) VALUES (?,'PRIVATE_PLAYER_SECRET',?,?)",
        "76561123456789" + hour, at, at);
    long id = jdbc.queryForObject("SELECT MAX(id) FROM M_CK_PLAYER", Long.class);
    jdbc.update("INSERT INTO T_CK_SESSION (ck_player_id,player_name,connected_at,disconnected_at) VALUES (?,'PRIVATE_PLAYER_SECRET',?,?)", id, at, at.plusMinutes(30));
    poi("house_old", 1000, 2000);
    kill(at, "zombieGuild" + hour, 1010, 2010, "source-" + hour);
  }

  private long poi(String name, int x, int z) {
    String hash = "guild-test-" + name + "-" + x + "-" + z;
    if (count("SELECT COUNT(*) FROM M_WORLD_POI WHERE source_hash = ?", hash) == 0) {
      jdbc.update("INSERT INTO M_WORLD_POI (source_hash,world_name,poi_name,category,x,y,z) VALUES (?,'PRIVATE_WORLD',?,'house',?,40,?)", hash, name, x, z);
    }
    return jdbc.queryForObject("SELECT id FROM M_WORLD_POI WHERE source_hash = ?", Long.class, hash);
  }

  private void walk(OffsetDateTime at, int x, int z, String mode, String hash) {
    jdbc.update("INSERT INTO T_PLAYER_POSITION_TRANSACTION (occurred_at,player_name,player_entity_id,position_x,position_z,position_source_type,"
        + "source_event_hash,source_file,movement_mode) VALUES (?,'PRIVATE_NAME',1,?,?,'TEST',?,'guild-test',?)", at, x, z, "guild-test-" + hash, mode);
  }

  /**
   * Core Keeper memories are a chest's gains, a chest at least half full, or a milestone. Spoilers stay
   * unnamed, chest labels and places never leave, and the AI reads the progress as it was then.
   */
  @Test void coreKeeperMemoriesComeFromChestsAndMilestones() {
    long world = jdbc.queryForObject("SELECT game_world_id FROM M_GAME_WORLD WHERE game_id = 'corekeeper' AND active = TRUE", Long.class);
    var lowered = LocalDate.now(GuildService.TOKYO).minusDays(20).atTime(15, 30).atZone(GuildService.TOKYO).toOffsetDateTime();
    long then = snapshot(world, lowered.minusDays(1), "a");
    long now = snapshot(world, LocalDate.now(GuildService.TOKYO).minusDays(1).atStartOfDay(GuildService.TOKYO).toOffsetDateTime(), "b");
    achieve(then, "boss_bird");
    for (String key : List.of("great_wall_lowered", "boss_bird")) achieve(now, key);
    // A half-full chest counts, a nearly empty one does not. Item names are absent in tests, so its memory reads back empty.
    for (int filled : new int[] {5, 1}) {
      jdbc.update("INSERT INTO T_CK_CONTAINER (game_world_id,world_snapshot_id,holder,object_id,variation,pos_x,pos_z,slot_count,filled_slots,label) "
          + "VALUES (?,?,'STORAGE',1,0,?,456,10,?,'PRIVATE_PLAYER_SECRETの箱')", world, now, 123 + filled, filled);
    }
    jdbc.update("INSERT INTO T_CK_CHEST_CHANGE (game_world_id,world_snapshot_id,chest_object_id,pos_x,pos_z,label,object_id,previous_amount,new_amount,observed_at) "
        + "VALUES (?,?,1,128,456,'PRIVATE_PLAYER_SECRETの箱',7,0,30,?)", world, now, lowered);
    assertThat(sources.inventory(LocalDate.now(GuildService.TOKYO))).containsExactly(
        new GuildSourceService.Inventory("corekeeper", "gain", 1), new GuildSourceService.Inventory("corekeeper", "milestone", 2),
        new GuildSourceService.Inventory("corekeeper", "chest", 1));
    java.util.Set<String> facts = new java.util.HashSet<>();
    for (int i = 0; i < 40; i++) sources.choose(LocalDate.now(GuildService.TOKYO)).source().ifPresent(source -> {
      assertThat(source.category()).isEqualTo("milestone");
      assertThat(source.label() + source.facts()).doesNotContain("PRIVATE").doesNotContain("Azeos").doesNotContain("#").doesNotContain("456");
      facts.add(source.facts());
    });
    // Done is done: no time, no "before records began".
    assertThat(facts).containsExactlyInAnyOrder("「大壁の解放」を成し遂げた", "嵐を呼ぶ空の巨鳥を討ち果たした");
    // The world is read as it was at a memory's time: at an earlier moment the bird was down, the wall not yet.
    var wall = new GuildSourceService.Source("corekeeper:milestone:great_wall_lowered", "corekeeper", "milestone", "x", "x", lowered);
    assertThat(dossier.build(wall)).contains("ボス討伐 1/").contains("世界の変化 0/1").contains("嵐を呼ぶ空の巨鳥").doesNotContain("Azeos");
  }

  @Test void sevenDaysMemoriesArePlacesReachedOnFootAndEnemiesDefeated() {
    var day = LocalDate.now(GuildService.TOKYO).minusDays(3);
    var noon = day.atTime(12, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    long house = poi("house_old", 1000, 2000);
    poi("store_pharmacy", 5000, 5000);
    walk(noon, 1020, 2030, "WALK", "walk");
    walk(noon, 5000, 5010, "VEHICLE", "drive");
    kill(noon.plusMinutes(10), "zombieGuildA", 1010, 2000, "a1");
    kill(noon.plusMinutes(20), "zombieGuildB", 3000, 3000, "b1");
    // World events, joins and the blood moon schedule are no longer memories.
    worldEvent(noon, "BLOOD_MOON", "moon");
    worldEvent(noon, "AIR_DROP", "air");
    assertThat(sources.inventory(LocalDate.now(GuildService.TOKYO))).containsExactly(
        new GuildSourceService.Inventory("7dtd", "place", 1), new GuildSourceService.Inventory("7dtd", "hunt", 2));
    java.util.Map<String, String> seen = new java.util.HashMap<>();
    for (int i = 0; i < 60; i++) sources.choose(LocalDate.now(GuildService.TOKYO)).source().ifPresent(source -> seen.put(source.key(), source.facts()));
    assertThat(seen).containsEntry("7dtd:place:" + house, "12:00 古い家に足を踏み入れた\n12:10 古い家でzombieGuildAを倒した")
        .containsEntry("7dtd:hunt:" + day + ":zombieGuildA", "12:10 古い家でzombieGuildAを倒した")
        .containsEntry("7dtd:hunt:" + day + ":zombieGuildB", "12:20 zombieGuildBを倒した");
    assertThat(seen.values()).allSatisfy(facts -> assertThat(facts).doesNotContain("PRIVATE").doesNotContain("薬局"));
  }

  private long snapshot(long world, OffsetDateTime at, String hash) {
    jdbc.update("INSERT INTO T_WORLD_SNAPSHOT (game_world_id,source_sha256,parser_version,captured_at,parsed_at,validation_status,extracted_json) "
        + "VALUES (?,?,'guild-test',?,?,'VALID','{}')", world, "guild-test-" + hash, at, at);
    return jdbc.queryForObject("SELECT world_snapshot_id FROM T_WORLD_SNAPSHOT WHERE source_sha256 = ?", Long.class, "guild-test-" + hash);
  }

  private void achieve(long snapshot, String key) {
    achieve(snapshot, "corekeeper", key);
  }

  private void achieve(long snapshot, String game, String key) {
    jdbc.update("INSERT INTO T_PROGRESS_STATE (world_snapshot_id,progress_definition_id,state) SELECT ?, progress_definition_id, 'ACHIEVED' "
        + "FROM M_PROGRESS_DEFINITION WHERE game_id = ? AND progress_key = ?", snapshot, game, key);
  }

  /** Terraria: a day's chest gains, bosses and events achieved (town NPCs are not), and chests at least half full. */
  @Test void terrariaMemoriesComeFromChestGainsMilestonesAndFullChests() {
    long world = jdbc.queryForObject("SELECT game_world_id FROM M_GAME_WORLD WHERE game_id = 'terraria' AND active = TRUE", Long.class);
    long now = snapshot(world, LocalDate.now(GuildService.TOKYO).minusDays(1).atStartOfDay(GuildService.TOKYO).toOffsetDateTime(), "t");
    for (String key : List.of("boss_eye", "event_goblins", "npc_goblin")) achieve(now, "terraria", key);
    var day = LocalDate.now(GuildService.TOKYO).minusDays(4).atTime(20, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    jdbc.update("INSERT INTO T_TERRARIA_ITEM_CHANGE (game_world_id,world_snapshot_id,item_id,previous_total,new_total,observed_at) VALUES "
        + "(?,?,1,0,5,?),(?,?,2,9,3,?)", world, now, day, world, now, day.minusDays(1));
    for (int filled : new int[] {20, 40, 3}) {
      jdbc.update("INSERT INTO T_TERRARIA_CONTAINER (game_world_id,world_snapshot_id,container_kind,tile_x,tile_y,name,slot_count,filled_slots,managed) "
          + "VALUES (?,?,'CHEST',?,10,'PRIVATE_PLAYER_SECRET',40,?,TRUE)", world, now, filled, filled);
    }
    try {
      assertThat(sources.inventory(LocalDate.now(GuildService.TOKYO))).containsExactly(
          new GuildSourceService.Inventory("terraria", "gain", 1), new GuildSourceService.Inventory("terraria", "milestone", 2),
          new GuildSourceService.Inventory("terraria", "chest", 2));
      java.util.Set<String> facts = new java.util.HashSet<>();
      for (int i = 0; i < 40; i++) sources.choose(LocalDate.now(GuildService.TOKYO)).source().ifPresent(source -> facts.add(source.facts()));
      assertThat(facts).containsExactlyInAnyOrder("「クトゥルフのめだま」を討ち果たした", "「ゴブリンしんりゃく」を退けた");
    } finally {
      jdbc.update("DELETE FROM T_TERRARIA_ITEM_CHANGE WHERE world_snapshot_id = ?", now);
      jdbc.update("DELETE FROM T_TERRARIA_CONTAINER WHERE world_snapshot_id = ?", now);
    }
  }

  @Test void anHourHoldsOneInteractionEvenWhenRunTwiceAtOnce() throws Exception {
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results = executor.invokeAll(List.<java.util.concurrent.Callable<String>>of(() -> interactions.runHour(HOUR), () -> interactions.runHour(HOUR)));
      for (var result : results) assertThat(result.get()).isNotNull();
    }
    assertThat(interactions.runHour(HOUR.plusMinutes(10))).contains("すでに処理済み");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_HOURLY_JOB WHERE status = 'SUCCEEDED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION_PARTICIPANT")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)", rine, roan, roan, rine)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT label FROM T_GUILD_BOND", String.class)).isEqualTo("acquaintance");
    assertThat(jdbc.queryForObject("SELECT written_by FROM T_GUILD_INTERACTION", String.class)).isEqualTo("ai");
    verify(bedrock, times(1)).generate(anyString(), argThat(input -> input.contains("リネ") && input.contains("ロアン")), eq(GuildInteractionWriter.MAX_TOKENS), any());
  }

  /** Sixty days of every hour with five residents: the rules hold however the cards fall. */
  @Test void manyHoursKeepTheRules() {
    List<Long> ids = List.of(resident("リネ", 28, "ALIVE", 0), resident("ロアン", 30, "ALIVE", 1), resident("ミラ", 45, "ALIVE", 2),
        resident("ガルド", 19, "ALIVE", 3), resident("セナ", 16, "ALIVE", 4));
    // Men and women, so gender shapes some pairs.
    jdbc.update("UPDATE M_GUILD_CHARACTER SET gender = '男性' WHERE id IN (?, ?)", ids.get(1), ids.get(3));
    // Different villages, factions and faiths, so affiliation cards can fall too.
    jdbc.update("UPDATE M_GUILD_CHARACTER SET faction = 'silver_knights', faith = 'dawnlight' WHERE id = ?", ids.get(1));
    jdbc.update("UPDATE M_GUILD_CHARACTER SET home_village = 'elmwood', current_village = 'elmwood', faction = 'verdant_wardens', faith = 'spirit' WHERE id = ?", ids.get(2));
    jdbc.update("UPDATE M_GUILD_CHARACTER SET faith = 'spirit' WHERE id = ?", ids.get(3));
    for (int day = 1; day <= 60; day++) {
      var date = HOUR.toLocalDate().plusDays(day);
      jdbc.update("INSERT INTO T_GUILD_DAY (visit_date) VALUES (?)", date);
      for (int seat = 0; seat < ids.size(); seat++) {
        jdbc.update("INSERT INTO T_GUILD_VISIT (visit_date,character_id,seat,morning,afternoon,evening) VALUES (?,?,?,'a','b','c')", date, ids.get(seat), seat);
      }
      if (day == 40) jdbc.update("UPDATE M_GUILD_CHARACTER SET status = 'DEAD' WHERE id = ?", ids.get(3));
      for (int hour = 0; hour < 24; hour++) {
        for (int minute : new int[] {3, 18, 33, 48}) interactions.runHour(date.atTime(hour, minute).atZone(GuildService.TOKYO));
      }
    }
    // While the server is up (20:00-23:30): a slot each quarter hour from 20:00 to 23:15, fourteen a day; at most twelve interactions.
    // Only the simulated days are counted: the real scheduler may run today's slot while the test runs in the evening.
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_HOURLY_JOB WHERE slot_key > ?", HOUR.toLocalDate().plusDays(1).toString())).isEqualTo(60 * 14);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isGreaterThan(60 * 11);
    assertThat(jdbc.queryForList("SELECT DISTINCT SUBSTRING(slot_key, 14, 3) FROM T_GUILD_HOURLY_JOB ORDER BY 1", String.class))
        .containsExactly(":00", ":15", ":30", ":45");
    assertThat(jdbc.queryForList("SELECT DISTINCT SUBSTRING(slot_key, 12, 2) FROM T_GUILD_HOURLY_JOB ORDER BY 1", String.class))
        .containsExactly("20", "21", "22", "23");
    assertThat(jdbc.queryForList("SELECT DISTINCT SUBSTRING(slot_key, 12, 5) FROM T_GUILD_HOURLY_JOB WHERE slot_key LIKE '%T23:%' ORDER BY 1", String.class))
        .containsExactly("23:00", "23:15");
    assertThat(count("SELECT MAX(n) FROM (SELECT COUNT(*) n FROM T_GUILD_INTERACTION GROUP BY SUBSTRING(slot_key, 1, 10)) t")).isLessThanOrEqualTo(12);
    // Every change an event made is in the history, and the last one is where the resident is now.
    for (long id : ids) {
      for (String[] kind : new String[][] {{"village", "current_village"}, {"faction", "faction"}, {"faith", "faith"}}) {
        List<String> last = jdbc.queryForList("SELECT to_value FROM T_GUILD_AFFILIATION_HISTORY WHERE character_id = ? AND kind = ? "
            + "ORDER BY changed_at DESC, id DESC LIMIT 1", String.class, id, kind[0]);
        if (!last.isEmpty()) {
          assertThat(jdbc.queryForObject("SELECT " + kind[1] + " FROM M_GUILD_CHARACTER WHERE id = ?", String.class, id)).isEqualTo(last.getFirst());
        }
      }
    }
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_AFFILIATION_HISTORY h WHERE h.interaction_id IS NOT NULL "
        + "AND NOT EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = h.interaction_id "
        + "AND p.character_id = h.character_id AND p.position = 0)")).isZero();
    assertThat(count("SELECT COUNT(*) FROM M_GUILD_CHARACTER c JOIN M_GUILD_FACTION f ON f.faction_key = c.faction "
        + "WHERE f.village_key IS NOT NULL AND f.village_key <> c.current_village")).as("members live where their faction is").isZero();
    assertThat(count("SELECT COUNT(DISTINCT card_id) FROM T_GUILD_INTERACTION")).isGreaterThan(10);
    assertThat(count("SELECT MAX(n) FROM (SELECT COUNT(*) n FROM T_GUILD_INTERACTION GROUP BY slot_key) t")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION i JOIN T_GUILD_INTERACTION_PARTICIPANT p ON p.interaction_id = i.id "
        + "WHERE p.character_id = ? AND i.happened_at >= ?", ids.get(3), HOUR.toLocalDate().plusDays(40).atStartOfDay(GuildService.TOKYO).toOffsetDateTime())).isZero();
    for (long id : ids) {
      assertThat(count("SELECT COUNT(*) FROM T_GUILD_BOND WHERE (low_id = ? OR high_id = ?) AND label IN ('lover','spouse')", id, id)).isLessThanOrEqualTo(1);
    }
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_BOND WHERE (low_id = ? OR high_id = ?) AND label IN ('lover','spouse')", ids.get(4), ids.get(4))).isZero();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION WHERE trust NOT BETWEEN -100 AND 100 OR affinity NOT BETWEEN -100 AND 100 "
        + "OR wariness NOT BETWEEN 0 AND 100 OR debt NOT BETWEEN 0 AND 100")).isZero();
    String labels = String.join(",", jdbc.queryForList("SELECT label || ':' || COUNT(*) FROM T_GUILD_BOND GROUP BY label ORDER BY label", String.class));
    String kinds = String.join(",", jdbc.queryForList("SELECT kind || ':' || COUNT(*) FROM T_GUILD_INTERACTION GROUP BY kind ORDER BY kind", String.class));
    String changes = String.join(",", jdbc.queryForList("SELECT change_kind || ':' || COUNT(*) FROM T_GUILD_AFFILIATION_HISTORY "
        + "GROUP BY change_kind ORDER BY change_kind", String.class));
    System.out.println("guild simulation labels=" + labels + " kinds=" + kinds + " changes=" + changes);
  }

  @Test void theDeadAndAnEmptyTavernMakeNoInteraction() {
    resident("リネ", 28, "ALIVE", 0);
    resident("ロアン", 30, "DEAD", 1);
    assertThat(interactions.runHour(HOUR)).contains("2人未満");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isZero();
    assertThat(interactions.jobs()).extracting(GuildInteractionService.Job::status).containsExactly("SKIPPED");
    // A skipped hour stays skipped: it is not filled in later.
    resident("ミラ", 25, "ALIVE", 2);
    assertThat(interactions.runHour(HOUR)).contains("すでに処理済み");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isZero();
    verify(bedrock, never()).generate(anyString(), anyString(), eq(GuildInteractionWriter.MAX_TOKENS), any());
  }

  @Test void aFailedWriterFallsBackToTheTemplateWithTheSameOutcome() {
    resident("リネ", 28, "ALIVE", 0);
    resident("ロアン", 30, "ALIVE", 1);
    when(bedrock.generate(anyString(), anyString(), eq(GuildInteractionWriter.MAX_TOKENS), any())).thenThrow(new IllegalStateException("down"));
    assertThat(interactions.runHour(HOUR)).doesNotContain("失敗");
    assertThat(jdbc.queryForObject("SELECT written_by FROM T_GUILD_INTERACTION", String.class)).isEqualTo("template");
    String body = jdbc.queryForObject("SELECT body FROM T_GUILD_INTERACTION", String.class);
    assertThat(body).isNotBlank().doesNotContain("{a}").doesNotContain("{b}");
  }

  /** A voice per resident from who they are; men and women, young and old, shy and blunt sound different, always the same. */
  @Test void residentsTalkInTheirOwnVoice() {
    assertThat(voices.of(1, 30, "女性", "冒険者", "明るく人懐っこい。", "none").key()).isEqualTo("cheerful_f");
    assertThat(voices.of(1, 30, "男性", "冒険者", "明るく人懐っこい。", "none").key()).isEqualTo("cheerful_m");
    assertThat(voices.of(2, 30, "男性", "鍛冶屋", "ぶっきらぼうで頑固。", "none").key()).isEqualTo("stern_m");
    assertThat(voices.of(2, 30, "女性", "鍛冶屋", "ぶっきらぼうで頑固。", "none").key()).isEqualTo("brisk_f");
    assertThat(voices.of(3, 24, "女性", "薬師", "内気で人見知り。", "none").first()).isEqualTo("わたし");
    assertThat(voices.of(4, 72, "男性", "農家", "明るい。", "none").describe()).startsWith("一人称「わし」");
    assertThat(voices.of(5, 12, "女性", "見習い", "元気。", "none").key()).isEqualTo("kid_f");
    assertThat(voices.of(6, 35, "男性", "侍", "まっすぐ。", "tsukikage_samurai").key()).isEqualTo("samurai_m");
    assertThat(voices.of(6, 35, "女性", "侍", "まっすぐ。", "月影武家衆").key()).isEqualTo("samurai_f");
    assertThat(voices.of(7, 40, "男性", "商人", "抜け目ない。", "none").key()).isEqualTo("polite");
    assertThat(voices.of(8, 28, "女性", "猟師", "一人称は「おいら」。気が強い。", "none").first()).isEqualTo("おいら");
    // Without telling words: one of the everyday voices for their gender, fixed by id.
    for (long id = 1; id <= 40; id++) {
      assertThat(voices.of(id, 30, "女性", "農家", "ふつう。", "none").key()).endsWith("_f").isEqualTo(voices.of(id, 30, "女性", "農家", "ふつう。", "none").key());
      assertThat(voices.of(id, 30, "男性", "農家", "ふつう。", "none").key()).endsWith("_m");
    }
    // Every idle line is short, plain text, and quoted when shown.
    for (var voice : voices.voices().values()) {
      for (String place : GuildVoice.PLACES) {
        var character = new GuildService.Character(9, LocalDate.of(2026, 1, 1), "7dtd", "客", "", "", "", "moss", "ALIVE",
            30, "女性", "農家", null, null, null, null, null, null, null, null, null);
        assertThat(voices.idle(character, place, LocalDate.of(2026, 10, 25), "evening")).startsWith("「").endsWith("」").hasSizeLessThan(40)
            .doesNotContain("<", ">", "@", "/");
      }
    }
  }

  /**
   * The werewolf table: the traveller and up to seven living residents, each with their lines in their own voice (the
   * game itself runs in the browser). Too few residents and there is no game.
   */
  @Test void residentsPlayWerewolfInTheirOwnVoice() throws Exception {
    for (int i = 0; i < 3; i++) resident("住人" + i, 30, "ALIVE", i);
    mvc.perform(get("/guild/werewolf")).andExpect(status().isOk())
        .andExpect(content().string(containsString("卓を囲めません")))
        .andExpect(content().string(not(containsString("data-wolf-player"))));
    for (int i = 3; i < 9; i++) resident("住人" + i, 30, "ALIVE", i);
    resident("故人", 30, "DEAD", 9);
    String page = mvc.perform(get("/guild/werewolf")).andExpect(status().isOk())
        .andExpect(content().string(containsString("data-co=\"私が占い師ですわ。黙っていられなくて。\"")))
        .andExpect(content().string(containsString("data-suspect=\"{t}さん、少し様子がおかしいかしら。\n{t}さんのお言葉、少し気になりますわ。\n")))
        .andExpect(content().string(containsString("/js/guild-werewolf.js")))
        .andExpect(content().string(containsString("data-hunter=\"")))
        .andExpect(content().string(containsString("data-wolf-table")))
        .andExpect(content().string(containsString("id=\"wolf-head-vote\"")))
        .andExpect(content().string(containsString("酒場の余興：人狼")))
        .andReturn().getResponse().getContentAsString();
    assertThat(page.split("data-wolf-player=", -1)).hasSize(GuildWerewolf.MAX + 1);
    assertThat(page).doesNotContain("故人");
    // A pastime in the guild's tavern, not a tab of its own. Its script is on the tavern too, since the window is
    // opened in place from there.
    mvc.perform(get("/guild")).andExpect(content().string(containsString("/js/guild-werewolf.js")))
        .andExpect(content().string(containsString("guild-game-link\" href=\"/guild/werewolf\"")));
    mvc.perform(get("/guild/log")).andExpect(content().string(not(containsString("</svg>人狼</a>"))));
    // Every voice has every line; a first person named in the personality is used.
    long own = resident("おいら", 30, "ALIVE", 10);
    jdbc.update("UPDATE M_GUILD_CHARACTER SET personality = '一人称は「おいら」。気が強い。' WHERE id = ?", own);
    assertThat(werewolf.lines(guild.character(own))).containsOnlyKeys(GuildWerewolf.KINDS).containsEntry("co", "おいらが占い師さ。隠してても仕方ないだろ。");
    assertThat(werewolf.lines(guild.character(own)).get("defend").split("\n")).hasSizeGreaterThan(1).allMatch(line -> !line.contains("{me}"));
    // How those at the table stand with each other goes to the game: numbers, ids and bond keys only.
    var table = werewolf.table(ZonedDateTime.now(GuildService.TOKYO));
    var seated = table.stream().map(player -> player.character().id()).filter(id -> id != own).toList();
    long a = seated.get(0);
    long b = seated.get(1);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt,smitten) VALUES (?,?,10,40,5,0,TRUE)", a, b);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,-20,-10,30,0)", b, a);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,0,0,0,0)", a, own);
    jdbc.update("INSERT INTO T_GUILD_BOND (low_id,high_id,label,since) VALUES (?,?,'rival',?)", Math.min(a, b), Math.max(a, b), OffsetDateTime.now());
    String ties = werewolf.ties(table);
    assertThat(ties.split(";")).contains(a + ":" + b + ":10:40:5:1", b + ":" + a + ":-20:-10:30:0");
    if (table.stream().noneMatch(player -> player.character().id() == own)) assertThat(ties).doesNotContain(":" + own + ":");
    assertThat(werewolf.bonds(table)).isEqualTo(Math.min(a, b) + ":" + Math.max(a, b) + ":rival");
    assertThat(werewolf.ties(List.of())).isEmpty();
    assertThat(werewolf.version()).isNotBlank();
  }

  /**
   * 潜む者: a full party of seven residents and the traveller, each seat with the lines the labyrinth uses (the game
   * itself runs in the browser). Fewer than seven living residents and there is no game.
   */
  @Test void residentsLoseThemselvesInTheLabyrinth() throws Exception {
    for (int i = 0; i < 6; i++) resident("住人" + i, 30, "ALIVE", i);
    mvc.perform(get("/guild/mimic")).andExpect(status().isOk())
        .andExpect(content().string(containsString("迷宮に挑めません")))
        .andExpect(content().string(not(containsString("data-mimic-player"))));
    resident("住人6", 30, "ALIVE", 6);
    resident("故人", 30, "DEAD", 7);
    String page = mvc.perform(get("/guild/mimic")).andExpect(status().isOk())
        .andExpect(content().string(containsString("酒場の余興：潜む者")))
        .andExpect(content().string(containsString("/js/guild-mimic.js")))
        .andExpect(content().string(containsString("data-mz-stage")))
        .andExpect(content().string(containsString("data-suspect=\"")))
        .andExpect(content().string(containsString("href=\"/guild/werewolf\"")))
        .andReturn().getResponse().getContentAsString();
    assertThat(page.split("data-mimic-player=", -1)).hasSize(GuildWerewolf.MAX + 1);
    assertThat(page).doesNotContain("故人");
    mvc.perform(get("/guild/werewolf")).andExpect(content().string(containsString("href=\"/guild/mimic\"")));
  }

  /**
   * The guild's gossip: bonds that changed, moves, newcomers and dungeon runs of the last seven days, in fixed words,
   * newest first; older ones and meeting for the first time are not told.
   */
  @Test void theTavernTellsWhatHappenedLately() throws Exception {
    mvc.perform(get("/guild")).andExpect(content().string(containsString("噂になるようなことは起きていない")));
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    long mira = resident("ミラ", 24, "ALIVE", 2);
    var now = OffsetDateTime.now();
    String[][] scenes = {{"news-1", "2"}, {"news-2", "1"}, {"news-3", "20"}, {"news-4", "1"}};
    for (String[] scene : scenes) {
      jdbc.update("INSERT INTO T_GUILD_INTERACTION (slot_key,happened_at,card_id,cards_version,kind,selection,outcome,summary,body,written_by) "
          + "VALUES (?,?,'c','v','雑談','random','warm','話した','本文','template')", scene[0], now.minusDays(Long.parseLong(scene[1])));
    }
    java.util.function.Function<String, Long> scene = key -> jdbc.queryForObject("SELECT id FROM T_GUILD_INTERACTION WHERE slot_key = ?", Long.class, key);
    String event = "INSERT INTO T_GUILD_RELATION_EVENT (interaction_id,from_id,to_id,change_kind,label_before,label_after,reason) VALUES (?,?,?,'label',?,?,'r')";
    jdbc.update(event, scene.apply("news-1"), rine, roan, "friend", "close_friend");
    jdbc.update(event, scene.apply("news-2"), rine, mira, "friend", "acquaintance");
    jdbc.update(event, scene.apply("news-3"), roan, mira, "friend", "lover");
    jdbc.update(event, scene.apply("news-4"), roan, mira, null, "acquaintance");
    jdbc.update("INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id,kind,from_value,to_value,change_kind,reason,changed_at) VALUES (?,'village','grantford','elmwood','move','r',?)",
        mira, now.minusHours(5));
    jdbc.update("INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id,kind,to_value,change_kind,reason,changed_at) VALUES (?,'village','grantford','birth','r',?)",
        roan, now.minusDays(3));
    jdbc.update("INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id,kind,to_value,change_kind,reason,changed_at) VALUES (?,'faith','spirit','birth','r',?)",
        roan, now.minusDays(3));
    var departure = now.minusDays(4);
    jdbc.update("INSERT INTO T_GUILD_EXPEDITION (expedition_date,dungeon_key,dungeon_name,goal,result,cards_version,departed_at,returned_at) "
        + "VALUES (?,'old-mine','古い坑道','探し物','partial','test',?,?)", departure.toLocalDate(), departure, departure.plusMinutes(40));
    long run = jdbc.queryForObject("SELECT id FROM T_GUILD_EXPEDITION", Long.class);
    jdbc.update("INSERT INTO T_GUILD_EXPEDITION_MEMBER (expedition_id,character_id,position) VALUES (?,?,0)", run, mira);
    jdbc.update("INSERT INTO T_GUILD_EXPEDITION_MEMBER (expedition_id,character_id,position) VALUES (?,?,1)", run, rine);

    assertThat(news.recent(ZonedDateTime.now(GuildService.TOKYO))).extracting(GuildNews.Item::text).containsExactly(
        "ミラがエルムウッドへ移り住んだそうだ。",
        "リネとミラは、もう友達ではないらしい。",
        "リネとロアンが親友になったらしい。",
        "見慣れない顔がいる。ロアンというらしい。",
        "ミラとリネが古い坑道から戻った。目的は半ばまで果たしたらしい。");
    mvc.perform(get("/guild")).andExpect(status().isOk())
        .andExpect(content().string(containsString("ギルドの噂話")))
        .andExpect(content().string(containsString("href=\"/guild/log/" + scene.apply("news-1") + "\">リネとロアンが親友になったらしい。</a>")))
        .andExpect(content().string(containsString("href=\"/guild/dungeon/" + run + "\"")));
    assertThat(GuildNews.bond("A", "B", "lover", "friend")).isEqualTo("AとBの仲が冷えて、友達に戻ったらしい。");
    assertThat(GuildNews.bond("A", "B", "friend", "spouse")).isEqualTo("AとBが結ばれたそうだ。");
  }

  /**
   * The relationship chart: the overview draws the living with a bond beyond acquaintance or an open crush; around one
   * resident it shows the ones they are most tied to, how each feels, open threads, and the bonds among them.
   */
  @Test void theRelationChartShowsBondsFeelingsAndCrushes() throws Exception {
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    long mira = resident("ミラ", 24, "ALIVE", 2);
    long gard = resident("ガルド", 40, "ALIVE", 3);
    long old = resident("故人", 70, "DEAD", 4);
    long stranger = resident("ひとり", 33, "ALIVE", 5);
    var now = OffsetDateTime.now();
    for (long[] bond : new long[][] {{rine, roan}, {rine, gard}, {roan, mira}, {rine, old}, {rine, stranger}}) {
      String label = bond[1] == roan ? "close_friend" : bond[1] == gard ? "rival" : bond[1] == mira ? "lover" : bond[1] == old ? "spouse" : "acquaintance";
      jdbc.update("INSERT INTO T_GUILD_BOND (low_id,high_id,label,since) VALUES (?,?,?,?)", Math.min(bond[0], bond[1]), Math.max(bond[0], bond[1]), label, now);
    }
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,20,50,0,0)", rine, roan);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,10,40,0,0)", roan, rine);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,-20,-40,60,0)", rine, gard);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,0,5,0,0)", rine, mira);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,0,45,0,0)", mira, rine);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,30,70,0,0)", rine, old);
    jdbc.update("INSERT INTO T_GUILD_INTERACTION (slot_key,happened_at,card_id,cards_version,kind,selection,outcome,summary,body,written_by) "
        + "VALUES ('map-test',?,'c','v','雑談','random','warm','ミラはリネに片思いしている','本文','template')", now.minusDays(30));
    long scene = jdbc.queryForObject("SELECT id FROM T_GUILD_INTERACTION WHERE slot_key = 'map-test'", Long.class);
    jdbc.update("INSERT INTO T_GUILD_THREAD (kind,a_id,b_id,summary,origin_interaction_id,expires_on) VALUES ('crush',?,?,'ミラはリネに片思いしている',?,?)",
        mira, rine, scene, LocalDate.now().plusDays(10));

    String all = mvc.perform(get("/guild/relations")).andExpect(status().isOk())
        .andExpect(content().string(containsString("rmap-edge e-close_friend")))
        .andExpect(content().string(containsString("rmap-edge e-rival")))
        .andExpect(content().string(containsString("rmap-edge e-lover")))
        .andExpect(content().string(containsString("marker-end=\"url(#rmap-arrow)\"")))
        .andExpect(content().string(containsString("href=\"/guild/relations?center=" + gard + "\"")))
        .andExpect(content().string(containsString("n-grantford")))
        .andReturn().getResponse().getContentAsString();
    // The dead and those who are only acquaintances are not in the overview.
    assertThat(all).doesNotContain("e-spouse", "relations?center=" + old + "\"", "relations?center=" + stranger + "\"");
    // Within one nation: only those who live there and the bonds between them.
    jdbc.update("UPDATE M_GUILD_CHARACTER SET current_village = 'elmwood' WHERE id = ?", mira);
    var grantford = new GuildRelationMap(jdbc, guild).overview("grantford");
    assertThat(grantford.nodes()).extracting(GuildRelationMap.Node::name).containsExactlyInAnyOrder("リネ", "ロアン", "ガルド");
    assertThat(grantford.edges()).extracting(GuildRelationMap.Edge::kind).containsExactlyInAnyOrder("close_friend", "rival");
    assertThat(new GuildRelationMap(jdbc, guild).overview("elmwood").empty()).isTrue();
    assertThat(new GuildRelationMap(jdbc, guild).overview("nowhere").nodes()).hasSize(4);
    mvc.perform(get("/guild/relations").param("nation", "grantford")).andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/guild/relations?nation=grantford\" aria-current=\"page\"")))
        .andExpect(content().string(not(containsString("relations?center=" + mira + "\""))));
    jdbc.update("UPDATE M_GUILD_CHARACTER SET current_village = 'grantford' WHERE id = ?", mira);

    var around = new GuildRelationMap(jdbc, guild).around(rine);
    // Those around her are the ones she has feelings about (an acquaintance she never met as a relation is not).
    assertThat(around.rows()).extracting(row -> row.other().name()).containsExactlyInAnyOrder("ロアン", "ガルド", "ミラ", "故人");
    var byName = around.rows().stream().collect(java.util.stream.Collectors.toMap(row -> row.other().name(), row -> row));
    assertThat(byName.get("ロアン")).extracting(GuildRelationMap.Row::label, GuildRelationMap.Row::toward, GuildRelationMap.Row::back)
        .containsExactly("親友", "好き", "好き");
    assertThat(byName.get("ガルド")).extracting(GuildRelationMap.Row::label, GuildRelationMap.Row::toward, GuildRelationMap.Row::back)
        .containsExactly("宿敵", "警戒している", "まだ知らない");
    assertThat(byName.get("ミラ").threads()).containsExactly("片思いされている");
    assertThat(byName.get("故人").other().dead()).isTrue();
    // The lovers around her are joined too; the crush is an arrow from Mira.
    assertThat(around.edges()).extracting(GuildRelationMap.Edge::kind).contains("lover", "close_friend", "rival", "spouse", "crush");
    mvc.perform(get("/guild/relations").param("center", String.valueOf(rine))).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネの相関図")))
        .andExpect(content().string(containsString("警戒している")))
        .andExpect(content().string(containsString("片思いされている")))
        .andExpect(content().string(containsString("href=\"/guild/pairs/" + Math.min(rine, roan) + "-" + Math.max(rine, roan) + "\"")));
    mvc.perform(get("/guild/relations").param("center", "999999")).andExpect(status().isNotFound());
    mvc.perform(get("/guild/characters/" + rine)).andExpect(content().string(containsString("href=\"/guild/relations?center=" + rine + "\">相関図</a>")));
    mvc.perform(get("/guild/log")).andExpect(content().string(containsString("href=\"/guild/relations\"")));
  }

  /** At most five new residents a day, even when the setting asks for more. */
  @Test void theDailyLimitStopsAtFive() {
    var p = properties;
    var asked = new GuildProperties(p.enabled(), p.recentDays(), p.recentRatio(), p.publicUrl(), 20, p.categoryWindow(), p.categoryCap(),
        p.genderCap(), p.occupationCap(), p.lineageCap(), p.interactionHours(), p.interactionEnd(), p.interactionDailyMax(), p.selectionWeights(),
        p.interactionFallback(), p.recentPairSlots(), p.dungeonDeparture(), p.dungeonStageMinutes());
    assertThat(asked.dailyLimit()).isEqualTo(5);
    assertThat(new GuildProperties(p.enabled(), p.recentDays(), p.recentRatio(), p.publicUrl(), 0, p.categoryWindow(), p.categoryCap(),
        p.genderCap(), p.occupationCap(), p.lineageCap(), p.interactionHours(), p.interactionEnd(), p.interactionDailyMax(), p.selectionWeights(),
        p.interactionFallback(), p.recentPairSlots(), p.dungeonDeparture(), p.dungeonStageMinutes()).dailyLimit()).isEqualTo(5);
  }

  /**
   * Where each one's mind is (V69): after a scene the AI writes what is on each one's mind and how they see the other; the
   * next scene between them is given that and what they talked about before, and the resident's window shows it.
   */
  @Test void residentsCarryWhatTheyThinkIntoTheNextScene() throws Exception {
    String minded = SCENE.strip().replaceFirst("}$", ",\"thoughts\":[{\"name\":\"リネ\",\"now\":\"冬に備えて薪を集めないと。\",\"about\":\"ロアンは気が利く人だ。\"},"
        + "{\"name\":\"ロアン\",\"now\":\"火の番を任されたのが少しうれしい。\",\"about\":\"リネは寒がりらしい。\"}]}");
    when(bedrock.generate(anyString(), anyString(), eq(GuildInteractionWriter.MAX_TOKENS), any())).thenReturn(minded);
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    interactions.runHour(HOUR);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_THOUGHT")).isEqualTo(4);
    assertThat(thoughts.now(rine)).isEqualTo("冬に備えて薪を集めないと。");
    assertThat(thoughts.about(roan, rine)).isEqualTo("リネは寒がりらしい。");
    // The first scene had nothing to recall; the next one between them is given each one's mind and their last talk.
    verify(bedrock, never()).generate(anyString(), argThat(input -> input.contains("今思っていること")), anyInt(), any());
    interactions.runHour(HOUR.plusMinutes(15));
    verify(bedrock).generate(argThat(prompt -> prompt.contains("続きのある会話")), argThat(input -> input.contains("リネが今思っていること=冬に備えて薪を集めないと。")
        && input.contains("ロアンがリネについて思っていること=リネは寒がりらしい。") && input.contains("前に二人で話したこと=")),
        eq(GuildInteractionWriter.MAX_TOKENS), any());
    // The window shows where the mind is now, how it got there, and how they see the other one.
    mvc.perform(get("/guild/characters/" + rine)).andExpect(status().isOk())
        .andExpect(content().string(containsString("今、思っていること")))
        .andExpect(content().string(containsString("冬に備えて薪を集めないと。")))
        .andExpect(content().string(containsString("class=\"mind-trail\"")))
        .andExpect(content().string(containsString("ロアンは気が利く人だ。")));
    // A talk is written with what is on their mind.
    when(bedrock.generate(anyString(), anyString(), eq(GuildTalkWriter.MAX_TOKENS), any())).thenReturn(TALK);
    talks.write(HOUR, GuildTalks.MANUAL);
    verify(bedrock).generate(anyString(), argThat(input -> input.contains("今思っていること=")), eq(GuildTalkWriter.MAX_TOKENS), any());
    // Deleting a resident takes the thoughts with the interactions.
    guild.delete(rine);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_THOUGHT")).isZero();
  }

  /**
   * The town talks (V71): big news reaches friends of the two as a rumor on their mind, which they murmur in the tavern
   * that day; a partner who hears of their lover growing close to another takes it badly; feelings not fed fade.
   */
  @Test void rumorsSpreadJealousyStingsAndFeelingsFade() throws Exception {
    when(bedrock.generate(anyString(), anyString(), anyInt(), any())).thenThrow(new RuntimeException("offline"));
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    interactions.runHour(HOUR);
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_INTERACTION", Long.class);
    // Two more come in later; one is fond of Rine, one barely knows her.
    long mira = resident("ミラ", 25, "ALIVE", 2);
    long gard = resident("ガルド", 40, "ALIVE", 3);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,10,30,0,0)", mira, rine);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,0,5,0,0)", gard, rine);
    assertThat(rumors.spread(id, rine, roan, "リネとロアンが恋仲になったらしい。", List.of())).containsExactly(mira);
    assertThat(thoughts.now(mira)).isEqualTo("リネとロアンが恋仲になったらしい。");
    assertThat(thoughts.now(gard)).isNull();
    assertThat(thoughts.rumorsOn(HOUR.toLocalDate())).containsOnlyKeys(mira);
    mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("リネとロアンが恋仲になったらしい。")));
    mvc.perform(get("/guild/characters/" + mira)).andExpect(status().isOk()).andExpect(content().string(containsString("class=\"mind-rumor\"")));
    // Gard is Rine's husband and hears that Roan has grown close to her.
    rumors.jealous(id, gard, "ガルド", rine, "リネ", roan, "ロアン");
    assertThat(jdbc.queryForMap("SELECT trust, affinity, wariness FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", gard, roan))
        .containsEntry("TRUST", 0).containsEntry("AFFINITY", -GuildRumors.JEALOUS_AFFINITY).containsEntry("WARINESS", GuildRumors.JEALOUS_WARINESS);
    assertThat(count("SELECT trust FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", gard, rine)).isEqualTo(-GuildRumors.JEALOUS_TRUST);
    assertThat(thoughts.now(gard)).isEqualTo("リネがロアンと親しくしているらしい。胸がざわつく。");
    assertThat(thoughts.about(gard, roan)).contains("気に入らない");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION_EVENT WHERE interaction_id = ? AND from_id = ? AND reason LIKE '噂：%'", id, gard)).isEqualTo(2);
    // Feelings not fed fade, a step each DRIFT_AFTER_DAYS, down to the floor; spouses don't drift.
    var now = HOUR.toOffsetDateTime();
    jdbc.update("UPDATE T_GUILD_RELATION SET affinity = 40, updated_at = ? WHERE from_id = ? AND to_id = ?", now.minusDays(20), mira, rine);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt,updated_at) VALUES (?,?,0,12,0,0,?)", mira, roan, now.minusDays(20));
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt,updated_at) VALUES (?,?,0,60,0,0,?)", rine, gard, now.minusDays(20));
    jdbc.update("INSERT INTO T_GUILD_BOND (low_id,high_id,label,since) VALUES (?,?,'spouse',?)", Math.min(rine, gard), Math.max(rine, gard), now.minusDays(30));
    rumors.drift(now);
    rumors.drift(now);
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", mira, rine)).isEqualTo(40 - GuildRumors.DRIFT);
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", mira, roan)).isEqualTo(GuildRumors.DRIFT_FLOOR);
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", rine, gard)).isEqualTo(60);
    rumors.drift(now.plusDays(GuildRumors.DRIFT_AFTER_DAYS + 1));
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", mira, rine)).isEqualTo(40 - 2 * GuildRumors.DRIFT);
    // A hearer can be deleted; so can the one the rumor is about.
    guild.delete(mira);
    guild.delete(roan);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_THOUGHT WHERE character_id = ? OR about_id = ?", mira, roan)).isZero();
  }

  /** What is passed on: code-made, from what code decided; everyday talk is not news. */
  @Test void onlyBigNewsIsPassedOn() {
    var a = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか");
    var b = new GuildInteractionService.Person(2, "ロアン", 30, "男性", "鍛冶屋", "無口");
    var pair = new GuildInteractionService.Pair(a, b, GuildInteractionService.Rel.NONE, GuildInteractionService.Rel.NONE, "friend", null, 3, 0, List.of(), null);
    assertThat(GuildInteractionService.news(pair, "friend", "lover", null)).isEqualTo("リネとロアンが恋仲になったらしい。");
    assertThat(GuildInteractionService.news(pair, "friend", "mentor", null)).isEqualTo("ロアンがリネに弟子入りしたそうだ。");
    assertThat(GuildInteractionService.news(pair, "acquaintance", "friend", null)).isNull();
    assertThat(GuildInteractionService.news(pair, "friend", "friend", null)).isNull();
    assertThat(GuildInteractionService.news(pair, null, "acquaintance", null)).isNull();
    var join = new GuildInteractionService.Change("join", "faction", "faction", "none", "silver_knights", "リネが銀の騎士団に加わる（加入）");
    assertThat(GuildInteractionService.news(pair, "friend", "friend", join)).isEqualTo("リネが銀の騎士団に加わると聞いた。");
  }

  /** Thoughts that don't hold are dropped; the scene stays. */
  @Test void thoughtsThatDoNotHoldAreDroppedButTheSceneStays() {
    var a = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか。");
    var b = new GuildInteractionService.Person(2, "ロアン", 30, "男性", "鍛冶屋", "無口。");
    var scene = new GuildInteractionWriter.Scene("evening", "雑談", "二人は天気の話をした", a, b, "ふつう", "ふつう", "知人", null, "", List.of(),
        null, null, null);
    String good = SCENE.strip().replaceFirst("}$", ",\"thoughts\":[{\"name\":\"リネさん\",\"now\":\"雨が止むといい。\",\"about\":null}]}");
    assertThat(writer.thoughts(good, scene)).containsExactly(new GuildInteractionWriter.Thought("リネ", "雨が止むといい。", null));
    for (String bad : List.of("{\"name\":\"ガルド\",\"now\":\"眠い。\"}", "{\"name\":\"リネ\",\"now\":\"ロアンと結婚したい。\"}",
        "{\"name\":\"リネ\",\"now\":\"" + "長".repeat(100) + "\"}", "{\"name\":\"リネ\",\"now\":\"a\"},{\"name\":\"リネ\",\"now\":\"b\"}")) {
      String json = SCENE.strip().replaceFirst("}$", ",\"thoughts\":[" + bad + "]}");
      assertThat(writer.thoughts(json, scene)).isEmpty();
      assertThat(writer.validate(json, scene)).contains("リネ「今日は冷えるね。」");
    }
    assertThat(writer.thoughts(SCENE, scene)).isEmpty();
  }

  /** The writer is told each one's voice; the tavern gets the whole talk, line by line, to play in turn. */
  @Test void theWriterHearsTheVoicesAndTheTavernPlaysTheTalk() {
    resident("リネ", 28, "ALIVE", 0);
    resident("ロアン", 30, "ALIVE", 1);
    interactions.runHour(HOUR);
    verify(bedrock).generate(argThat(prompt -> prompt.contains("話し方") && prompt.contains("4〜8個")),
        argThat(input -> input.contains("リネの話し方=一人称「") && input.contains("ロアンの話し方=一人称「")), eq(GuildInteractionWriter.MAX_TOKENS), any());
    var scene = chronicle.scene(HOUR.plusMinutes(5), HOUR.toLocalDate());
    assertThat(scene.script()).extracting(GuildChronicle.Turn::text).containsExactly("「今日は冷えるね。」", "「火のそばに座ろう。」");
    long rine = jdbc.queryForObject("SELECT id FROM M_GUILD_CHARACTER WHERE name = 'リネ'", Long.class);
    assertThat(scene.turnsOf(rine)).singleElement().extracting(GuildChronicle.Turn::index).isEqualTo(0);
  }

  /** Signed-out visitors see only the guild icon in the header (no login either); signed-in members keep the portal and the game sites. */
  @Test void signedOutVisitorsSeeNoPortalInTheGuildHeader() throws Exception {
    for (String path : List.of("/guild", "/guild/log", "/guild/dungeon")) {
      mvc.perform(get(path)).andExpect(status().isOk())
          .andExpect(content().string(not(containsString("site-links"))))
          .andExpect(content().string(not(containsString("aria-label=\"ポータル\""))))
          .andExpect(content().string(not(containsString("href=\"/login\""))));
    }
    if (accounts.findByLoginId("guild-header-member").isEmpty()) {
      var account = new com.yuki.rubinco.account.entity.M_WebAccount();
      account.setLoginId("guild-header-member");
      account.setPasswordHash("unused-test-hash");
      account.setRole("MEMBER");
      accounts.save(account);
    }
    mvc.perform(get("/guild").with(user("guild-header-member").roles("MEMBER"))).andExpect(status().isOk())
        .andExpect(content().string(containsString("aria-label=\"ポータル\"")));
  }

  @Test void writerAcceptsOnlyParticipantsAndChangesCodeMade() {
    var a = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか");
    var b = new GuildInteractionService.Person(2, "ロアン", 30, "男性", "鍛冶屋", "無口");
    var scene = new GuildInteractionWriter.Scene("evening", "雑談", "二人は天気の話をした", a, b, "ふつう", "ふつう",
        "知人", null, "", List.of(), null, null, null);
    assertThat(writer.validate(SCENE, scene)).startsWith("夕方の酒場。").contains("リネ「今日は冷えるね。」");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("ロアン", "ガルド"), scene)).isInstanceOf(IllegalArgumentException.class);
    String married = SCENE.replace("火のそばに座ろう。", "俺たち、結婚しよう。いや結婚した。夫婦になった。");
    assertThatThrownBy(() -> writer.validate(married, scene)).hasMessageContaining("決まっていない変化");
    var wedding = new GuildInteractionWriter.Scene("evening", "求婚", "二人は夫婦になった", a, b, "深い愛情", "深い愛情",
        "恋人", "夫婦", "", List.of(), null, null, null);
    assertThat(writer.validate(married, wedding)).contains("夫婦になった");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("今日は冷えるね。", "彼は亡くなったよ。"), scene)).hasMessageContaining("決まっていない変化");
    // Joining a faction or a conversion only when code made it here, or when one of them went through it before.
    String joined = SCENE.replace("今日は冷えるね。", "騎士団への加入、おめでとう。");
    assertThatThrownBy(() -> writer.validate(joined, scene)).hasMessageContaining("決まっていない変化");
    var invite = new GuildInteractionWriter.Scene("evening", "勧誘", "リネは白銀の騎士団に加わった", a, b, "ふつう", "ふつう",
        "知人", null, "", List.of(), null, null, null, "派閥 白銀の騎士団：正義と名誉を重んじる騎士団。",
        "リネが白銀の騎士団に加わる（加入）", "join");
    assertThat(writer.validate(joined, invite)).contains("加入");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("今日は冷えるね。", "改宗したんだ。"), invite)).hasMessageContaining("決まっていない変化");
    var convert = new GuildInteractionService.Affiliation("grantford", "grantford", "none", "dawnlight", "グランフォード", "無所属", "暁光教",
        null, null, java.util.Set.of("convert"), List.of("10月1日 信仰：無信仰 → 暁光教（改宗）：ロアンに導かれた"));
    var changed = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか", convert);
    var later = new GuildInteractionWriter.Scene("evening", "雑談", "二人は天気の話をした", changed, b, "ふつう", "ふつう",
        "知人", null, "", List.of(), null, null, null);
    assertThat(writer.validate(SCENE.replace("今日は冷えるね。", "改宗して半月になる。"), later)).contains("改宗");
    // What the model often does is fine: a note on the name, a line a little over 60 characters, 殺風景 (not killing).
    String loose = SCENE.replace("\"speaker\":\"リネ\"", "\"speaker\":\"リネ（28歳）\"")
        .replace("火のそばに座ろう。", "火のそばに座ろう。".repeat(9)).replace("今日は冷えるね。", "殺風景な夜だね。");
    assertThat(writer.validate(loose, scene)).contains("リネ「殺風景な夜だね。」").contains("ロアン「火のそばに座ろう。火のそば");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("今日は冷えるね。", "あいつを殺してやる。"), scene)).hasMessageContaining("決まっていない変化");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("\"speaker\":\"リネ\"", "\"speaker\":\"リネア\""),
        new GuildInteractionWriter.Scene("evening", "雑談", "二人は天気の話をした", a, new GuildInteractionService.Person(3, "リネア", 30, "男性", "鍛冶屋", "無口"),
            "ふつう", "ふつう", "知人", null, "", List.of(), null, null, null))).hasMessageContaining("二人以外");
    // The reason a reply was refused says what was wrong, never the reply itself.
    assertThatThrownBy(() -> writer.validate("{\"narration\":\"夜。\",\"lines\":[", scene)).hasMessage("JSONとして読めない");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("今日は冷えるね。", "冷".repeat(120)), scene)).hasMessage("せりふが長すぎる");
  }

  /** A scene splits into speech by either participant and narration; anything else stays narration. */
  @Test void aSceneReadsAsAScript() {
    var people = List.of(new GuildChronicle.Ref(1, "リネ", "ALIVE", ""), new GuildChronicle.Ref(2, "ロアン", "ALIVE", ""));
    var entry = new GuildChronicle.Entry(1, java.time.OffsetDateTime.now(), "雑談", "random", "", "雨の夜。\nリネ「冷えるね。」\nロアン「そうだな。」\nガルド「誰だ？」",
        "ai", "guild", people);
    assertThat(entry.script()).extracting(GuildChronicle.Line::side).containsExactly(-1, 0, 1, -1);
    assertThat(entry.script().get(2)).extracting(GuildChronicle.Line::name, GuildChronicle.Line::text).containsExactly("ロアン", "そうだな。");
    assertThat(entry.script().get(3).text()).isEqualTo("ガルド「誰だ？」");
  }

  /** A refused reply is asked for once more; when that fails too, the fixed scene is kept and the maintenance page says why. */
  @Test void aRefusedSceneIsAskedForAgainAndTheReasonIsKept() {
    resident("リネ", 28, "ALIVE", 0);
    resident("ロアン", 30, "ALIVE", 1);
    when(bedrock.generate(anyString(), anyString(), eq(GuildInteractionWriter.MAX_TOKENS), any())).thenReturn("{\"narration\":", SCENE);
    interactions.runHour(HOUR);
    assertThat(jdbc.queryForObject("SELECT written_by FROM T_GUILD_INTERACTION", String.class)).isEqualTo("ai");
    when(bedrock.generate(anyString(), anyString(), eq(GuildInteractionWriter.MAX_TOKENS), any())).thenReturn(SCENE.replace("ロアン", "ガルド"));
    interactions.runHour(HOUR.withHour(21));
    assertThat(jdbc.queryForObject("SELECT written_by FROM T_GUILD_INTERACTION WHERE slot_key LIKE '%T21:00'", String.class)).isEqualTo("template");
    assertThat(interactions.jobs().getFirst().message()).contains("定型文（AIの応答を不採用：二人以外が話している）");
    verify(bedrock, times(4)).generate(anyString(), anyString(), eq(GuildInteractionWriter.MAX_TOKENS), any());
  }

  /** Event cards that change a faction and a faith: code applies both, keeps the history and shows it. */
  @Test void eventsChangeFactionsAndFaithsAndKeepTheHistory() throws Exception {
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    jdbc.update("UPDATE M_GUILD_CHARACTER SET occupation = '騎士', faction = 'silver_knights', faith = 'dawnlight' WHERE id = ?", roan);
    var only = new GuildCards(new org.springframework.core.io.ClassPathResource("guild/affiliation-test-cards.yml"));
    var service = new GuildInteractionService(jdbc, properties, only, writer, world, thoughts, rumors);
    service.runHour(HOUR.withHour(20));
    service.runHour(HOUR.withHour(21));
    assertThat(jdbc.queryForMap("SELECT faction, faith, current_village FROM M_GUILD_CHARACTER WHERE id = ?", rine))
        .containsEntry("FACTION", "silver_knights").containsEntry("FAITH", "dawnlight").containsEntry("CURRENT_VILLAGE", "grantford");
    assertThat(jdbc.queryForList("SELECT change_kind FROM T_GUILD_AFFILIATION_HISTORY WHERE character_id = ? AND interaction_id IS NOT NULL "
        + "ORDER BY change_kind", String.class, rine)).containsExactly("convert", "join");
    // A third slot: neither change is possible any more, so nothing is drawn.
    assertThat(service.runHour(HOUR.withHour(22))).contains("合う出来事がありません");
    // The writer saw both people's affiliations, only their parts of the world, and the change being made.
    verify(bedrock, atLeastOnce()).generate(anyString(), argThat(input -> input.contains("リネの所属=暮らす村 グランフォード")
        && input.contains("派閥 白銀の騎士団：") && !input.contains("月影郷") && input.contains("この場面で起きる所属の変化=リネが")), eq(GuildInteractionWriter.MAX_TOKENS), any());
    mvc.perform(get("/guild/characters/" + rine)).andExpect(status().isOk())
        .andExpect(content().string(containsString("所属の履歴")))
        .andExpect(content().string(containsString("派閥：無所属 → 白銀の騎士団（加入）")))
        .andExpect(content().string(containsString("信仰：無信仰 → 暁光教（改宗）")));
    long joined = jdbc.queryForObject("SELECT interaction_id FROM T_GUILD_AFFILIATION_HISTORY WHERE change_kind = 'join'", Long.class);
    mvc.perform(get("/guild/log/" + joined)).andExpect(status().isOk())
        .andExpect(content().string(containsString("所属の変化")))
        .andExpect(content().string(containsString("リネ ／ 派閥：無所属 → 白銀の騎士団（加入）")));
    mvc.perform(get("/guild/maintenance").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
        .andExpect(content().string(containsString("村・派閥・信仰の内訳")))
        .andExpect(content().string(containsString("毎日 20:00〜23:30（日本時間）の15分ごと")));
    guild.delete(rine);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_AFFILIATION_HISTORY")).isZero();
  }

  /** Different genders warm slowly unless one finds the other their type; feelings can go one way only. */
  @Test void genderShapesHowFastEachSideWarms() {
    var woman = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか");
    var man = new GuildInteractionService.Person(2, "ロアン", 30, "男性", "鍛冶屋", "無口");
    var other = new GuildInteractionService.Person(3, "ミラ", 25, "女性", "薬師", "明るい");
    var cool = new GuildInteractionService.Rel(0, 0, 0, 0);
    var mixed = new GuildInteractionService.Pair(woman, man, cool, cool, null, null, 0, 0, List.of(), null);
    var same = new GuildInteractionService.Pair(woman, other, cool, cool, null, null, 0, 0, List.of(), null);
    var gain = java.util.Map.of("affinity", 4, "trust", 2);
    assertThat(GuildInteractionService.attraction(gain, same, cool)).isEqualTo(gain);
    for (int i = 0; i < 30; i++) {
      assertThat(GuildInteractionService.attraction(gain, mixed, cool).get("affinity")).isIn(0, 3);
    }
    assertThat(GuildInteractionService.attraction(gain, mixed, new GuildInteractionService.Rel(0, 0, 0, 0, true))).containsEntry("affinity", 7).containsEntry("trust", 2);
    assertThat(GuildInteractionService.attraction(gain, mixed, new GuildInteractionService.Rel(0, 30, 0, 0))).containsEntry("affinity", 4);
    assertThat(GuildInteractionService.attraction(java.util.Map.of("affinity", -3), mixed, cool)).containsEntry("affinity", -3);
  }

  /**
   * Relations move at the pace of twelve interactions a day: each meeting that goes no worse than even brings both sides a
   * little closer, close pairs move their story on, and a big event whose moment is ripe comes up far more often.
   */
  @Test void relationsMoveAtTheTavernsPace() {
    assertThat(GuildInteractionService.familiar(java.util.Map.of("affinity", 6))).containsEntry("affinity", 9).containsEntry("trust", 2);
    assertThat(GuildInteractionService.familiar(java.util.Map.of("affinity", -6))).containsEntry("affinity", -6).doesNotContainKey("trust");
    assertThat(GuildInteractionService.familiar(java.util.Map.of("wariness", 3))).doesNotContainKey("affinity");
    var cards = new GuildCards().cards();
    var weather = cards.stream().filter(card -> card.id().equals("weather-talk")).findFirst().orElseThrow();
    var confess = cards.stream().filter(card -> card.id().equals("confess")).findFirst().orElseThrow();
    var a = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか");
    var b = new GuildInteractionService.Person(2, "ロアン", 30, "男性", "鍛冶屋", "無口");
    java.util.function.BiFunction<Integer, String, GuildInteractionService.Pair> pair = (affinity, label) -> new GuildInteractionService.Pair(a, b,
        new GuildInteractionService.Rel(20, affinity, 0, 0), new GuildInteractionService.Rel(20, affinity, 0, 0), label, null, 3, 0, List.of(), null);
    assertThat(GuildInteractionService.depth(weather, pair.apply(10, "acquaintance"))).isEqualTo(1);
    assertThat(GuildInteractionService.depth(confess, pair.apply(10, "acquaintance"))).isEqualTo(1);
    assertThat(GuildInteractionService.depth(weather, pair.apply(45, "friend"))).isEqualTo(1);
    assertThat(GuildInteractionService.depth(confess, pair.apply(45, "friend"))).isEqualTo(GuildInteractionService.DEPTH);
    assertThat(GuildInteractionService.depth(confess, pair.apply(55, "friend"))).isEqualTo(GuildInteractionService.DEPTH * GuildInteractionService.RIPE);
  }

  /** Now and then a visitor comes with someone they like, who takes the seat of the last one in line. */
  @Test void visitorsSometimesBringSomeoneTheyLike() {
    List<Long> line = new java.util.ArrayList<>();
    for (int i = 0; i < 11; i++) line.add(resident("住人" + i, 30, "ALIVE", i));
    long rine = line.get(0);
    long roan = line.remove(10);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,20,40,0,0)", rine, roan);
    boolean came = false;
    for (int i = 0; i < 40; i++) {
      List<Long> seated = ReflectionTestUtils.invokeMethod(guild, "companions", line, GuildPlaces.CENTER, List.of());
      assertThat(seated).hasSize(10).doesNotHaveDuplicates();
      if (seated.get(1) == roan) {
        came = true;
        assertThat(seated).doesNotContain(line.get(9));
      } else {
        assertThat(seated).isEqualTo(line);
      }
      // Someone already elsewhere today stays there.
      assertThat((List<Long>) ReflectionTestUtils.invokeMethod(guild, "companions", line, GuildPlaces.CENTER, List.of(roan))).isEqualTo(line);
    }
    assertThat(came).isTrue();
  }

  /** Interactions run in the evening, so every card must be able to fall then; gender and taste cards are read. */
  @Test void everyCardCanFallInTheEvening() {
    var cards = new GuildCards().cards();
    assertThat(cards).hasSizeGreaterThan(170);
    // Every placeholder a card writes is one code fills.
    var known = java.util.Set.of("{a}", "{b}", "{dead}", "{a_job}", "{b_job}", "{a_home}", "{b_home}", "{a_faction}", "{b_faction}",
        "{a_faith}", "{b_faith}", "{a_village}", "{b_village}");
    for (var card : cards) {
      for (var outcome : card.outcomes()) {
        String text = outcome.summary() + outcome.template() + (outcome.open() == null ? "" : outcome.open().summary());
        var used = java.util.regex.Pattern.compile("\\{[a-z_]+}").matcher(text);
        while (used.find()) assertThat(known).as(card.id()).contains(used.group());
      }
    }
    var a = new GuildInteractionService.Person(1, "リネ", 28, "女性", "薬師", "穏やか",
        new GuildInteractionService.Affiliation("tsukikage", "elmwood", "none", "none", "エルムウッド", "無所属", "無信仰", null, null,
            java.util.Set.of(), List.of(), "月影郷"));
    var b = new GuildInteractionService.Person(2, "ロアン", 30, "男性", null, "無口");
    var pair = new GuildInteractionService.Pair(a, b, GuildInteractionService.Rel.NONE, GuildInteractionService.Rel.NONE, null, null, 0, 0, List.of(), null);
    assertThat(GuildInteractionService.fill("{a}は{a_job}、{a_home}の生まれ。{b}は{b_job}、{b_home}の生まれ。", pair))
        .isEqualTo("リネは薬師、月影郷の生まれ。ロアンは暮らし、グランフォードの生まれ。");
    assertThat(cards).allMatch(card -> card.when().phases().isEmpty() || card.when().phases().contains("evening"));
    assertThat(cards).anyMatch(card -> "different".equals(card.when().gender())).anyMatch(card -> card.when().smitten());
  }

  @Test void oneSidedFeelingsBecomeACrush() throws Exception {
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    jdbc.update("UPDATE M_GUILD_CHARACTER SET gender = '男性' WHERE id = ?", roan);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt,smitten) VALUES (?,?,10,45,0,0,TRUE)", rine, roan);
    jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt,smitten) VALUES (?,?,10,0,0,0,FALSE)", roan, rine);
    var quiet = new GuildCards(new org.springframework.core.io.ClassPathResource("guild/crush-test-cards.yml"));
    new GuildInteractionService(jdbc, properties, quiet, writer, world, thoughts, rumors).runHour(HOUR);
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", rine, roan)).isEqualTo(45 + 15);
    // The card's 2 at the pace (×3) and getting used to each other (+3) is 9; smitten makes it 9 + 5 + 1, reserved 0 or 6.
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", roan, rine)).isIn(0, 6);
    assertThat(jdbc.queryForMap("SELECT kind, a_id, summary FROM T_GUILD_THREAD")).containsEntry("KIND", "crush")
        .containsEntry("A_ID", rine).containsEntry("SUMMARY", "リネはロアンに片思いしている");
    verify(bedrock).generate(anyString(), argThat(input -> input.contains("二人は異性") && input.contains("好みのタイプで気になっている")
        && input.contains("この場面で生まれること=リネはロアンに片思いしている")), eq(GuildInteractionWriter.MAX_TOKENS), any());
    mvc.perform(get("/guild/characters/" + rine)).andExpect(status().isOk()).andExpect(content().string(containsString("気になっている")));
    mvc.perform(get("/guild/characters/" + roan)).andExpect(status().isOk()).andExpect(content().string(not(containsString("気になっている"))));
    // The log says who is who.
    mvc.perform(get("/guild/log")).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネ（28歳・女性・冒険者・グランフォード） × ロアン（30歳・男性・冒険者・グランフォード）")));
  }

  /** Men and women get their own hair, accessories and cuts, and every part they can get is drawn in the sprite sheet. */
  @Test void looksAreSplitByGenderAndEveryPartIsDrawn() throws Exception {
    String sheet = new String(new org.springframework.core.io.ClassPathResource("static/img/guild-people.svg").getInputStream().readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    java.util.Set<String> drawn = new java.util.HashSet<>();
    var ids = java.util.regex.Pattern.compile("<symbol id=\"([^\"]+)\"").matcher(sheet);
    while (ids.find()) drawn.add(ids.group(1));
    java.util.Set<String> outfits = new java.util.HashSet<>();
    for (String occupation : GuildPeople.OUTFITS.keySet()) {
      for (int i = 0; i < 30; i++) {
        var man = people.look("男性", occupation);
        var woman = people.look("女性", occupation);
        assertThat(GuildPeople.MALE_HAIR).contains(man.hair());
        assertThat(GuildPeople.FEMALE_HAIR).contains(woman.hair());
        assertThat(GuildPeople.MALE_ACCESSORIES).contains(man.accessory());
        assertThat(GuildPeople.FEMALE_ACCESSORIES).contains(woman.accessory());
        assertThat(man.layers()).contains("face-m");
        assertThat(woman.layers()).contains("face-f");
        assertThat(drawn).containsAll(man.layers()).containsAll(woman.layers());
        outfits.add(man.outfit());
        outfits.add(woman.outfit());
      }
    }
    assertThat(outfits).contains("samurai", "samurai_f", "ronin", "miko", "miko_m", "farmer_f", "merchant_f", "watch_f");
    // Hair, accessories and every women's cut actually differ from the men's.
    assertThat(GuildPeople.MALE_HAIR).doesNotContainAnyElementsOf(GuildPeople.FEMALE_HAIR);
    for (String cut : GuildPeople.WOMEN.values()) assertThat(GuildPeople.OUTFITS.values()).doesNotContain(cut);
  }

  @Test void theTavernSeatsTen() throws Exception {
    for (int i = 0; i < 12; i++) {
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance) "
          + "VALUES (?,'7dtd',?,'旅の者。','記録から。','穏やか。','moss')", LocalDate.now(GuildService.TOKYO).minusDays(300 + i), "客" + (char) ('ア' + i * 2));
    }
    // Everyone lives in Grantford (the default nation): ten go to the guild, the other two to Grantford's tavern.
    guild.prepareToday();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT WHERE place = 'guild'")).isEqualTo(10);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT WHERE place = 'grantford' AND seat BETWEEN 10 AND 14")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT WHERE place NOT IN ('guild', 'grantford')")).isZero();
    String tavern = mvc.perform(get("/guild")).andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"guild-area-counter\"")))
        .andExpect(content().string(containsString("for=\"guild-area-hearth\"")))
        .andExpect(content().string(containsString("tv-cork")))
        .andReturn().getResponse().getContentAsString();
    // No seat-number narration any more: each one says something of their own at their place.
    assertThat(tavern).doesNotContain("奥の卓で杯を傾けている").contains("」</span>");
    // All ten are somewhere in the five places, once each, and the map shows a dot for each.
    assertThat(tavern.split("class=\"room-person ", -1)).hasSize(11);
    String map = tavern.substring(tavern.indexOf("class=\"tv-map\""));
    assertThat(map.substring(0, map.indexOf("</div>")).split("<i></i>", -1)).hasSize(11);
  }

  /**
   * Three nations around the guild: each day the guild draws ten from everyone and each nation's tavern five more
   * from those who live there and are not at the guild. Each place has its own page in its own style.
   */
  @Test void theThreeNationsEachHaveATavernOfFive() throws Exception {
    // Fifteen in each nation: however the guild's ten fall, each nation keeps at least five.
    String[] nations = {"grantford", "elmwood", "tsukikage"};
    for (int i = 0; i < 45; i++) {
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance,home_village,current_village) "
          + "VALUES (?,'7dtd',?,'旅の者。','記録から。','穏やか。','moss',?,?)", LocalDate.now(GuildService.TOKYO).minusDays(300 + i),
          "民" + i, nations[i % 3], nations[i % 3]);
    }
    guild.prepareToday();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT WHERE place = 'guild'")).isEqualTo(10);
    for (var town : GuildPlaces.TOWNS) {
      assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT WHERE place = ?", town.key())).isEqualTo(5);
      assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT v JOIN M_GUILD_CHARACTER c ON c.id = v.character_id "
          + "WHERE v.place = ? AND (c.current_village <> ? OR v.seat NOT BETWEEN ? AND ?)", town.key(), town.nation(), town.base(), town.base() + 4)).isZero();
    }
    assertThat(count("SELECT COUNT(DISTINCT character_id) FROM T_GUILD_VISIT")).isEqualTo(25);
    // Run again: nothing more is drawn.
    guild.prepareToday();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT")).isEqualTo(25);

    String town = mvc.perform(get("/guild/towns/tsukikage")).andExpect(status().isOk())
        .andExpect(content().string(containsString("town-tsukikage")))
        .andExpect(content().string(containsString("月見茶屋 朧")))
        .andExpect(content().string(containsString("href=\"/guild/towns/tsukikage\" aria-current=\"page\"")))
        .andReturn().getResponse().getContentAsString();
    assertThat(town.split("class=\"room-person ", -1)).hasSize(6);
    // The dungeon and the tavern map belong to the guild only.
    assertThat(town).doesNotContain("guild-view-dungeon").doesNotContain("tv-map");
    mvc.perform(get("/guild/towns/atlantis")).andExpect(status().isNotFound());
    mvc.perform(get("/guild/towns/guild")).andExpect(status().isNotFound());
  }

  /** The first slot of each hour always happens at the guild; the other three at any place where two or more are in. */
  @Test void theGuildTalksEveryHourAndTheSecondSlotGoesAnywhere() {
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    long mira = resident("ミラ", 45, "ALIVE", 10);
    long gald = resident("ガルド", 19, "ALIVE", 11);
    jdbc.update("UPDATE T_GUILD_VISIT SET place = 'grantford' WHERE character_id IN (?, ?)", mira, gald);
    for (int hour = 20; hour < 24; hour++) interactions.runHour(HOUR.withHour(hour).withMinute(3));
    assertThat(jdbc.queryForList("SELECT DISTINCT place FROM T_GUILD_INTERACTION", String.class)).containsExactly("guild");
    assertThat(jdbc.queryForList("SELECT DISTINCT p.character_id FROM T_GUILD_INTERACTION_PARTICIPANT p", Long.class))
        .containsExactlyInAnyOrder(rine, roan);
    for (int hour = 20; hour < 23; hour++) interactions.runHour(HOUR.withHour(hour).withMinute(18));
    // With one left at the guild, the other slots can only go to Grantford.
    jdbc.update("UPDATE M_GUILD_CHARACTER SET status = 'DEAD' WHERE id = ?", roan);
    for (int hour = 20; hour < 23; hour++) interactions.runHour(HOUR.withHour(hour).withMinute(33));
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION WHERE place = 'grantford'")).isGreaterThanOrEqualTo(3);
    // Each talk happens among those at its place only.
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION i JOIN T_GUILD_INTERACTION_PARTICIPANT p ON p.interaction_id = i.id "
        + "JOIN T_GUILD_VISIT v ON v.character_id = p.character_id AND v.visit_date = ? WHERE v.place <> i.place", HOUR.toLocalDate())).isZero();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isEqualTo(10);
    // Past the end of the evening (the server stops at 23:30), nothing happens.
    assertThat(interactions.runHour(HOUR.withHour(23).withMinute(33))).contains("枠ではありません");
    assertThat(interactions.runHour(HOUR.withHour(19).withMinute(48))).contains("枠ではありません");
    // A nation's talk is written as happening in that nation's tavern.
    verify(bedrock, atLeastOnce()).generate(anyString(), argThat(input -> input.contains("場所=樫の盾亭（グランフォード）")), eq(GuildInteractionWriter.MAX_TOKENS), any());
  }

  /**
   * Talks: the AI writes one for a resident in some place today, once a day for the evening and once from
   * maintenance; visitors read the first line and three answers, and fetch one reply at a time.
   */
  @Test void residentsTalkWithVisitorsOnceADayEach() throws Exception {
    when(bedrock.generate(anyString(), anyString(), eq(GuildTalkWriter.MAX_TOKENS), any())).thenReturn(TALK);
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 10);
    jdbc.update("UPDATE T_GUILD_VISIT SET place = 'grantford' WHERE character_id = ?", roan);
    assertThat(talks.write(HOUR, GuildTalks.MANUAL)).contains("作りました");
    assertThat(talks.write(HOUR, GuildTalks.MANUAL)).contains("1日1回");
    assertThat(talks.writeScheduled(HOUR.withHour(22).withMinute(30))).contains("作りました");
    assertThat(talks.writeScheduled(HOUR.withHour(22).withMinute(40))).contains("すでに");
    // Two talks today, for two different residents, each in the place they are today.
    assertThat(jdbc.queryForList("SELECT character_id FROM T_GUILD_TALK", Long.class)).containsExactlyInAnyOrder(rine, roan);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_TALK WHERE character_id = ? AND place = 'grantford'", roan)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_TALK_CHOICE")).isEqualTo(6);
    verify(bedrock, atLeastOnce()).generate(anyString(), argThat(input -> input.contains("話し方=一人称") && input.contains("今いる店=樫の盾亭（グランフォード）")),
        eq(GuildTalkWriter.MAX_TOKENS), any());

    var talk = talks.latest(rine);
    assertThat(talk.labels()).containsExactly("たしかに少し濃いですね", "何が入っているんですか", "あなたの舌が鈍っているのでは");
    // Talking happens only at a meal: the resident's window has no talk, the tavern no talk marks; the tab follows the place.
    mvc.perform(get("/guild/characters/" + rine)).andExpect(status().isOk())
        .andExpect(content().string(not(containsString("data-talk"))));
    mvc.perform(get("/guild")).andExpect(content().string(not(containsString("person-talkable"))))
        .andExpect(content().string(containsString("href=\"/guild/meal\"")));
    mvc.perform(get("/guild/towns/tsukikage")).andExpect(content().string(containsString("href=\"/guild/towns/tsukikage/meal\"")));
    // Each place has its own menu, drawn in its own style; the menu reads without JS.
    mvc.perform(get("/guild/meal")).andExpect(status().isOk())
        .andExpect(content().string(containsString("menu-board")))
        .andExpect(content().string(containsString("data-meal-item=\"tap-ale\"")))
        .andExpect(content().string(containsString("data-pour=\"1\"")))
        .andExpect(content().string(containsString("data-a=\"bean-soup\" data-b=\"hardtack\"")))
        .andExpect(content().string(not(containsString("店主"))));
    mvc.perform(get("/guild/towns/tsukikage/meal")).andExpect(status().isOk())
        .andExpect(content().string(containsString("menu-tags")))
        .andExpect(content().string(containsString("月見団子")))
        .andExpect(content().string(containsString("data-place=\"tsukikage\"")))
        .andExpect(content().string(containsString("毎度おおきに")));
    mvc.perform(get("/guild/towns/grantford/meal")).andExpect(content().string(containsString("menu-parchment")));
    mvc.perform(get("/guild/towns/elmwood/meal")).andExpect(content().string(containsString("menu-bark")));
    mvc.perform(get("/guild/towns/nowhere/meal")).andExpect(status().isNotFound());
    mvc.perform(get("/guild/towns/guild/meal")).andExpect(status().isNotFound());
    // Sitting down brings someone with a talk who was not talked with today, those who talk at this place first,
    // with what they like best here and the clue to it.
    var favorite = menus.favorite(roan, "grantford");
    for (int i = 0; i < 5; i++) {
      mvc.perform(get("/guild/neighbor").param("place", "grantford")).andExpect(status().isOk())
          .andExpect(content().string(containsString("data-character-id=\"" + roan + "\"")))
          .andExpect(content().string(containsString("data-favorite=\"" + favorite.key() + "\" data-hint=\"" + favorite.hint() + "\"")))
          .andExpect(content().string(not(containsString("guild-window"))));
      mvc.perform(get("/guild/neighbor").param("place", "guild").param("skip", "x,<b>")).andExpect(status().isOk())
          .andExpect(content().string(containsString("data-character-id=\"" + rine + "\"")));
      // Nobody of this place left: anyone else sits down.
      mvc.perform(get("/guild/neighbor").param("place", "grantford").param("skip", roan + ",x")).andExpect(status().isOk())
          .andExpect(content().string(containsString("data-character-id=\"" + rine + "\"")));
    }
    mvc.perform(get("/guild/neighbor").param("place", "nowhere")).andExpect(status().isNotFound());
    String page = mvc.perform(get("/guild/neighbor").param("skip", String.valueOf(roan))).andExpect(status().isOk())
        .andExpect(content().string(containsString("今夜は少し塩が強い")))
        .andExpect(content().string(containsString("data-talk-choice=\"2\"")))
        .andExpect(content().string(containsString("data-place=\"" + talk.place() + "\"")))
        .andReturn().getResponse().getContentAsString();
    // The replies are not in the page: each comes when its answer is picked.
    assertThat(page).doesNotContain("おかわり、頼んじゃいましょうか");
    mvc.perform(get("/guild/talks/" + talk.id() + "/choices/1")).andExpect(status().isOk())
        .andExpect(content().string(containsString("店主の秘密")));
    mvc.perform(get("/guild/talks/" + talk.id() + "/choices/3")).andExpect(status().isNotFound());
    mvc.perform(get("/guild/talks/999999/choices/0")).andExpect(status().isNotFound());
    // Everyone talked with today: nobody sits down.
    mvc.perform(get("/guild/neighbor").param("skip", rine + "," + roan)).andExpect(status().isNotFound());
    // A favorite is a paid item of that place's menu, fixed for the resident, and residents differ.
    var liked = new java.util.HashSet<String>();
    for (long id = 1; id <= 60; id++) {
      var item = menus.favorite(id, "tsukikage");
      assertThat(item.price()).isPositive();
      assertThat(menus.of("tsukikage").item(item.key())).isEqualTo(item);
      assertThat(menus.favorite(id, "tsukikage")).isEqualTo(item);
      liked.add(item.key());
    }
    assertThat(liked).hasSizeGreaterThan(4);
    // The dead don't talk; deleting a resident takes their talks too.
    jdbc.update("UPDATE M_GUILD_CHARACTER SET status = 'DEAD' WHERE id = ?", rine);
    assertThat(talks.latest(rine)).isNull();
    mvc.perform(get("/guild/talks/" + talk.id() + "/choices/1")).andExpect(status().isNotFound());
    guild.delete(roan);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_TALK WHERE character_id = ?", roan)).isZero();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_TALK_CHOICE")).isEqualTo(3);
    // Nobody left to talk: nobody sits down.
    mvc.perform(get("/guild/neighbor")).andExpect(status().isNotFound());
  }

  @Test void talksNeedSomeoneInAPlaceAndAValidText() throws Exception {
    assertThat(talks.write(HOUR, GuildTalks.MANUAL)).contains("住人がいない");
    resident("リネ", 28, "ALIVE", 0);
    resident("ミナト", 30, "ALIVE", 1);
    for (String bad : List.of(TALK.replace("あなたはどうです", "リネやミナトはどうです"), TALK.replace("おかわり", "結婚"), "{\"opening\":\"短い\"}", "not json")) {
      when(bedrock.generate(anyString(), anyString(), eq(GuildTalkWriter.MAX_TOKENS), any())).thenReturn(bad);
      assertThat(talks.write(HOUR, GuildTalks.MANUAL)).contains("書けませんでした");
    }
    when(bedrock.generate(anyString(), anyString(), eq(GuildTalkWriter.MAX_TOKENS), any())).thenThrow(new IllegalStateException("down"));
    // The evening tries three times, then waits for the next day.
    for (int i = 0; i < GuildTalks.SCHEDULE_TRIES; i++) assertThat(talks.writeScheduled(HOUR)).contains("呼び出しの失敗");
    assertThat(talks.writeScheduled(HOUR)).contains("上限");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_TALK")).isZero();
    // From maintenance: administrators only, with CSRF.
    reset(bedrock);
    when(bedrock.generate(anyString(), anyString(), eq(GuildTalkWriter.MAX_TOKENS), any())).thenReturn(TALK.replace("あなたはどうです", "あなたは"));
    mvc.perform(post("/guild/maintenance/talk").with(user("member").roles("PLAYER")).with(csrf())).andExpect(status().isForbidden());
    mvc.perform(post("/guild/maintenance/talk").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    mvc.perform(post("/guild/maintenance/talk").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_TALK WHERE origin = 'manual'")).isEqualTo(1);
    mvc.perform(get("/guild/maintenance").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
        .andExpect(content().string(containsString("今日はメンテから作成済み")));
  }

  /** Whoever talked today shares a table, the newest talk at the first; the rest spread out the same way all day part. */
  @Test void theTavernSeatsTodaysTalkersTogether() {
    var visits = new java.util.ArrayList<GuildService.Visit>();
    for (long id = 1; id <= 10; id++) {
      var character = new GuildService.Character(id, LocalDate.of(2026, 1, 1), "7dtd", "客" + id, "", "", "", "moss", "ALIVE",
          30, "male", "", null, null, null, null, null, null, null, null, null);
      visits.add(new GuildService.Visit(character, (int) id - 1, "…"));
    }
    var date = LocalDate.of(2026, 10, 25);
    var layout = GuildTavern.arrange(visits, List.of(List.of(4L, 7L), List.of(2L, 9L), List.of(4L, 1L), List.of(5L, 99L)), java.util.Set.of(4L, 7L), java.util.Set.of(), date, "evening");
    var tables = layout.area("tables").seats();
    assertThat(tables.stream().filter(seat -> seat.slot() < 3).map(seat -> seat.visit().character().id())).contains(4L, 7L);
    assertThat(tables.stream().filter(seat -> seat.slot() >= 3).map(seat -> seat.visit().character().id())).contains(2L, 9L);
    assertThat(layout.areas().stream().mapToInt(GuildTavern.Area::count).sum()).isEqualTo(10);
    assertThat(layout.areas().stream().flatMap(area -> area.seats().stream()).map(seat -> seat.visit().character().id()).distinct()).hasSize(10);
    assertThat(layout.current()).isEqualTo("tables");
    assertThat(layout.area("tables").talk()).isTrue();
    // Same day and part of the day, same room; the order (and so the places) moves on with the day.
    assertThat(GuildTavern.arrange(visits, List.of(), java.util.Set.of(), java.util.Set.of(), date, "evening"))
        .isEqualTo(GuildTavern.arrange(visits, List.of(), java.util.Set.of(), java.util.Set.of(), date, "evening"));
    assertThat(GuildTavern.arrange(List.of(), List.of(), java.util.Set.of(), java.util.Set.of(), date, "morning").current()).isEqualTo("counter");
    assertThat(layout.area("party").count()).isZero();
    // Back from the dungeon: the party sits together at the round table, which the room opens on (no talk going on).
    var back = GuildTavern.arrange(visits, List.of(List.of(3L, 6L)), java.util.Set.of(), java.util.Set.of(3L, 6L, 8L), date, "evening");
    assertThat(back.area("party").seats().stream().map(seat -> seat.visit().character().id())).containsExactlyInAnyOrder(3L, 6L, 8L);
    assertThat(back.area("tables").seats()).noneMatch(seat -> java.util.Set.of(3L, 6L, 8L).contains(seat.visit().character().id()));
    assertThat(back.current()).isEqualTo("party");
  }

  /** Once a run is back, its party feel it both ways: closer after a success, mostly strained after a retreat. Once only. */
  @Test void aDungeonRunChangesHowItsPartyFeelOnceBack() throws Exception {
    List<Long> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 3; i++) ids.add(resident("同行" + (char) ('ア' + i * 2), 22 + i, "ALIVE", i));
    // Last night's run, so its page is open whatever the time of day the test runs.
    var departure = HOUR.minusDays(1).withHour(21).withMinute(50);
    jdbc.update("INSERT INTO T_GUILD_EXPEDITION (expedition_date,dungeon_key,dungeon_name,goal,result,cards_version,departed_at,returned_at) "
        + "VALUES (?,'old-mine','古い坑道','古い坑道で探し物をする','success','test',?,?)", departure.toLocalDate(), departure.toOffsetDateTime(),
        departure.plusMinutes(40).toOffsetDateTime());
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_EXPEDITION", Long.class);
    for (int i = 0; i < ids.size(); i++) jdbc.update("INSERT INTO T_GUILD_EXPEDITION_MEMBER (expedition_id,character_id,position) VALUES (?,?,?)", id, ids.get(i), i);
    assertThat(dungeon.settleDue(departure.plusMinutes(39))).isZero();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION")).isZero();
    assertThat(dungeon.settleDue(departure.plusMinutes(40))).isEqualTo(1);
    assertThat(dungeon.settleDue(departure.plusMinutes(41))).isZero();
    // Six directions among three, each warmer in trust; logged against the run, not an interaction.
    assertThat(jdbc.queryForList("SELECT trust FROM T_GUILD_RELATION", Integer.class)).hasSize(6).allMatch(trust -> trust >= 5 && trust <= 9);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION_EVENT WHERE expedition_id = ? AND interaction_id IS NULL", id)).isEqualTo(6);
    var bonds = dungeon.bonds(id);
    assertThat(bonds).hasSize(3).allMatch(bond -> bond.feeling().equals("絆が深まった"));
    assertThat(bonds.getFirst().numbers()).contains("同行ア→同行イ 信頼+");
    mvc.perform(get("/guild/dungeon/" + id)).andExpect(status().isOk()).andExpect(content().string(containsString("仲間との関係")))
        .andExpect(content().string(containsString("同行アと同行イ：絆が深まった")));
    // Relation pages that list interaction changes still open.
    mvc.perform(get("/guild/characters/" + ids.getFirst())).andExpect(status().isOk());

    // A retreat the next day: trust falls or (sometimes) rises a little; wariness only ever grows.
    jdbc.update("INSERT INTO T_GUILD_EXPEDITION (expedition_date,dungeon_key,dungeon_name,goal,result,cards_version,departed_at,returned_at) "
        + "VALUES (?,'old-mine','古い坑道','古い坑道で探し物をする','retreat','test',?,?)", HOUR.toLocalDate().plusDays(1),
        departure.plusDays(1).toOffsetDateTime(), departure.plusDays(1).plusMinutes(40).toOffsetDateTime());
    long retreat = jdbc.queryForObject("SELECT id FROM T_GUILD_EXPEDITION WHERE result = 'retreat'", Long.class);
    for (int i = 0; i < 2; i++) jdbc.update("INSERT INTO T_GUILD_EXPEDITION_MEMBER (expedition_id,character_id,position) VALUES (?,?,?)", retreat, ids.get(i), i);
    assertThat(dungeon.settleDue(departure.plusDays(1).plusMinutes(45))).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT trust_delta FROM T_GUILD_RELATION_EVENT WHERE expedition_id = ?", Integer.class, retreat))
        .hasSize(2).allMatch(delta -> (delta >= -5 && delta <= -2) || (delta >= 2 && delta <= 4));
    assertThat(jdbc.queryForList("SELECT wariness_delta FROM T_GUILD_RELATION_EVENT WHERE expedition_id = ?", Integer.class, retreat)).allMatch(delta -> delta >= 0);

    assertThat(guild.delete(ids.getFirst())).contains("削除しました");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION_EVENT")).isZero();
  }

  /** 21:50: a party leaves; its stages come out at 22:00, 22:10, 22:20 and 22:30, the result with the last. */
  @Test void aDungeonRunUnfoldsInFourStagesAndKeepsItsPartyOutOfTheTavern() {
    List<Long> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) ids.add(resident("冒険" + (char) ('ア' + i * 2), 20 + i, "ALIVE", i));
    // The seventh sits in a nation's tavern today, so the party comes from the six at the guild.
    jdbc.update("UPDATE T_GUILD_VISIT SET place = 'elmwood', seat = 15 WHERE character_id = ?", ids.get(6));
    var departure = HOUR.withHour(21).withMinute(50);
    assertThat(dungeon.departIfDue(departure.minusMinutes(1))).contains("出発の時間帯ではありません");
    assertThat(dungeon.departIfDue(departure.plusMinutes(40))).contains("出発の時間帯ではありません");
    assertThat(dungeon.departIfDue(departure)).contains("へ向かいました").contains("22:30");
    assertThat(dungeon.depart(departure.plusMinutes(1))).contains("すでに出発しています");
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_EXPEDITION", Long.class);
    List<Long> party = jdbc.queryForList("SELECT character_id FROM T_GUILD_EXPEDITION_MEMBER ORDER BY position", Long.class);
    assertThat(party).hasSizeBetween(1, 4).allMatch(ids::contains);
    assertThat(party).as("only from the guild").doesNotContain(ids.get(6));

    var start = dungeon.expedition(id, departure.plusMinutes(5));
    assertThat(start.stages()).hasSize(4).noneMatch(GuildDungeon.Stage::revealed);
    assertThat(start.result()).isNull();
    assertThat(start.status()).isEqualTo(start.dungeon() + "へ向かっている途中");
    assertThat(start.members()).extracting(GuildChronicle.Ref::id).containsExactlyElementsOf(party);
    var middle = dungeon.expedition(id, departure.plusMinutes(25));
    assertThat(middle.stages()).extracting(GuildDungeon.Stage::revealed).containsExactly(true, true, false, false);
    assertThat(middle.reached()).isEqualTo("承");
    assertThat(middle.back()).isFalse();
    assertThat(middle.status()).startsWith(middle.dungeon() + "で").endsWith("中");
    assertThat(dungeon.expedition(id, departure.plusMinutes(35)).doing())
        .isEqualTo(jdbc.queryForObject("SELECT doing FROM T_GUILD_EXPEDITION_STAGE WHERE stage = 3", String.class)).isNotEqualTo("探索中");
    assertThat(middle.stages().get(0).body()).doesNotContain("{");
    var end = dungeon.expedition(id, departure.plusMinutes(40));
    assertThat(end.stages()).allMatch(GuildDungeon.Stage::revealed);
    assertThat(end.result()).isIn("success", "partial", "retreat");
    assertThat(end.status()).isEqualTo(end.dungeon() + "から帰還（" + end.resultName() + "）");
    String last = jdbc.queryForObject("SELECT card_id FROM T_GUILD_EXPEDITION_STAGE WHERE stage = 4", String.class);
    assertThat(dungeon.deck().stages().get("ketsu")).filteredOn(card -> card.id().equals(last)).singleElement()
        .extracting(GuildDungeon.Card::result).isEqualTo(end.result());
    // Not readable before it leaves; summaries hide the result until the party is back.
    assertThatThrownBy(() -> dungeon.expedition(id, departure.minusMinutes(1))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(dungeon.recent(departure.plusMinutes(15), null, 5)).singleElement().extracting(GuildDungeon.Summary::result).isNull();
    assertThat(dungeon.recent(departure.plusMinutes(40), party.getFirst(), 5)).singleElement()
        .extracting(GuildDungeon.Summary::result).isEqualTo(end.result());

    // Out during the run, back in their seats afterwards with a line about it.
    assertThat(dungeon.presence(HOUR.toLocalDate(), departure.plusMinutes(15))).containsOnlyKeys(party).containsValue(null);
    assertThat(dungeon.presence(HOUR.toLocalDate(), departure.plusMinutes(40)).values()).allMatch(line -> line.startsWith("ダンジョンから戻り"));
    assertThat(dungeon.presence(HOUR.toLocalDate(), departure.minusMinutes(5))).isEmpty();

    // The 22:00 and 22:30 interactions are drawn only among those still in the tavern.
    for (var slot : List.of(departure.plusMinutes(13), departure.plusMinutes(43))) interactions.runHour(slot);
    assertThat(jdbc.queryForList("SELECT character_id FROM T_GUILD_INTERACTION_PARTICIPANT", Long.class)).noneMatch(party::contains);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isEqualTo(2);

    assertThat(guild.delete(party.getFirst())).contains("削除しました");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_EXPEDITION")).isZero();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_EXPEDITION_STAGE")).isZero();
  }

  static final String STORY = """
      {"stages":["リネは松明を掲げ、湿った空気の流れる入口へ一歩踏み込んだ。足もとで小石が転がり、闇の奥へ吸い込まれていった。",
       "リネは曲がり角ごとに壁へ小さな印を刻み、水の滴る音を数えながら、ゆっくりと奥へ進んでいった。",
       "通路の先で何かが動いた。リネは息をひそめて身をかがめ、松明の火を手のひらで覆いながら、その気配が通り過ぎるのを待った。",
       "夜がふける頃、リネは見てきたものを胸にしまい、来た道の印をひとつずつたどって、静かに酒場への帰り道についた。"]}
      """;

  /** Right after leaving, the AI retells the four stages once, from the card outlines; the cards stay when it can't. */
  @Test void theAiRetellsADungeonRunBeforeItsFirstStage() {
    resident("リネ", 24, "ALIVE", 0);
    long minato = resident("ミナト", 30, "ALIVE", 1);
    jdbc.update("DELETE FROM T_GUILD_VISIT WHERE character_id = ?", minato);
    when(bedrock.generate(anyString(), anyString(), eq(GuildDungeonWriter.MAX_TOKENS), any())).thenReturn(STORY);
    var departure = HOUR.withHour(21).withMinute(50);
    assertThat(dungeon.retellDue(departure)).isFalse();
    dungeon.depart(departure);
    List<String> cards = jdbc.queryForList("SELECT body FROM T_GUILD_EXPEDITION_STAGE ORDER BY stage", String.class);
    assertThat(dungeon.retellDue(departure.plusMinutes(1))).isTrue();
    assertThat(jdbc.queryForList("SELECT written_by FROM T_GUILD_EXPEDITION_STAGE", String.class)).hasSize(4).containsOnly("ai");
    assertThat(jdbc.queryForList("SELECT body FROM T_GUILD_EXPEDITION_STAGE ORDER BY stage", String.class))
        .hasSize(4).allMatch(body -> body.contains("リネ")).doesNotContainAnyElementsOf(cards);
    // The writer got each stage's hero and outline, the result, and nothing about who stayed home.
    verify(bedrock).generate(anyString(), argThat(input -> input.contains("起：主役=リネ／筋=" + cards.getFirst()) && input.contains("結果=")
        && input.contains("仲間=リネ（24歳・女性・冒険者）") && !input.contains("ミナト")), eq(GuildDungeonWriter.MAX_TOKENS), any());
    assertThat(dungeon.retellDue(departure.plusMinutes(2))).isFalse();
    assertThat(dungeon.expedition(jdbc.queryForObject("SELECT id FROM T_GUILD_EXPEDITION", Long.class), departure.plusMinutes(10))
        .latest().body()).isEqualTo(jdbc.queryForObject("SELECT body FROM T_GUILD_EXPEDITION_STAGE WHERE stage = 1", String.class));

    // Tomorrow the AI names someone who stayed home: the cards stay, and it is not asked again.
    when(bedrock.generate(anyString(), anyString(), eq(GuildDungeonWriter.MAX_TOKENS), any())).thenReturn(STORY.replace("夜がふける頃、リネは", "夜がふける頃、リネとミナトは"));
    var tomorrow = departure.plusDays(1);
    jdbc.update("INSERT INTO T_GUILD_DAY (visit_date) VALUES (?)", tomorrow.toLocalDate());
    jdbc.update("INSERT INTO T_GUILD_VISIT (visit_date,character_id,seat,morning,afternoon,evening) SELECT ?, id, 0, 'a', 'b', 'c' "
        + "FROM M_GUILD_CHARACTER WHERE name = 'リネ'", tomorrow.toLocalDate());
    dungeon.depart(tomorrow);
    assertThat(dungeon.retellDue(tomorrow.plusMinutes(1))).isFalse();
    assertThat(dungeon.retellDue(tomorrow.plusMinutes(2))).isFalse();
    verify(bedrock, times(2)).generate(anyString(), anyString(), eq(GuildDungeonWriter.MAX_TOKENS), any());
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_EXPEDITION_STAGE s JOIN T_GUILD_EXPEDITION e ON e.id = s.expedition_id "
        + "WHERE e.expedition_date = ? AND s.written_by = 'template'", tomorrow.toLocalDate())).isEqualTo(4);

    // Too late: once a stage could be out before the text is in place, the run is left as drawn.
    var later = departure.plusDays(2);
    jdbc.update("INSERT INTO T_GUILD_DAY (visit_date) VALUES (?)", later.toLocalDate());
    jdbc.update("INSERT INTO T_GUILD_VISIT (visit_date,character_id,seat,morning,afternoon,evening) SELECT ?, id, 0, 'a', 'b', 'c' "
        + "FROM M_GUILD_CHARACTER WHERE name = 'リネ'", later.toLocalDate());
    dungeon.depart(later);
    assertThat(dungeon.retellDue(later.plusMinutes(9))).isFalse();
    verify(bedrock, times(2)).generate(anyString(), anyString(), eq(GuildDungeonWriter.MAX_TOKENS), any());
  }

  /** Any party, any cards: every placeholder filled, {other} only with company, the ending fits the result. */
  @Test void dungeonRunsHoldTheirRulesForAnyParty() {
    var random = new java.util.Random(7);
    List<String> jobs = List.of("騎士", "薬師", "猟師", "大工", "商人", "農家");
    java.util.Set<String> results = new java.util.HashSet<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < 3000; i++) {
      int size = 1 + random.nextInt(4);
      List<GuildDungeon.Member> party = new java.util.ArrayList<>();
      for (int m = 0; m < size; m++) {
        party.add(new GuildDungeon.Member(m + 1, "人" + "アイウエ".charAt(m), jobs.get(random.nextInt(jobs.size())),
            List.of("grantford", "elmwood", "tsukikage").get(random.nextInt(3))));
      }
      var run = dungeon.simulate(party, random);
      assertThat(run.stages()).hasSize(4);
      assertThat(run.stages()).extracting(GuildDungeon.Drawn::body).noneMatch(body -> body.contains("{") || body.contains("}"));
      assertThat(run.stages()).allMatch(drawn -> party.contains(drawn.hero()) && party.size() >= drawn.card().min());
      assertThat(run.stages().getLast().card().result()).isEqualTo(run.result());
      assertThat(run.goal()).contains(run.dungeon().name());
      results.add(run.result());
      run.stages().forEach(drawn -> seen.add(drawn.card().id()));
    }
    assertThat(results).containsExactlyInAnyOrder("success", "partial", "retreat");
    // Every card can come up.
    assertThat(seen).hasSize(dungeon.deck().stages().values().stream().mapToInt(List::size).sum());
    assertThat(GuildDungeon.party(List.of("リネ", "ロアン", "カイ"))).isEqualTo("リネ、ロアン、カイの三人");
  }

  @Test void theDungeonWindowShowsOnlyWhatHasComeOut() throws Exception {
    for (int i = 0; i < 3; i++) resident("探索" + (char) ('ア' + i * 2), 25 + i, "ALIVE", i);
    mvc.perform(get("/guild/dungeon")).andExpect(status().isOk()).andExpect(content().string(containsString("まだ誰もダンジョンへ向かっていません")));
    mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("今夜の探索はまだです")))
        .andExpect(content().string(containsString("guild-dungeon-button")));
    dungeon.depart(ZonedDateTime.now(GuildService.TOKYO));
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_EXPEDITION", Long.class);
    List<Long> party = jdbc.queryForList("SELECT character_id FROM T_GUILD_EXPEDITION_MEMBER", Long.class);
    List<String> bodies = jdbc.queryForList("SELECT body FROM T_GUILD_EXPEDITION_STAGE ORDER BY stage", String.class);
    var page = mvc.perform(get("/guild/dungeon")).andExpect(status().isOk())
        .andExpect(content().string(containsString(jdbc.queryForObject("SELECT dungeon_name FROM T_GUILD_EXPEDITION", String.class))))
        .andExpect(content().string(containsString("href=\"/guild/dungeon\" aria-current=\"page\"")))
        .andExpect(content().string(containsString("探索中です"))).andReturn().getResponse().getContentAsString();
    for (String body : bodies) assertThat(page).doesNotContain(body);
    mvc.perform(get("/guild/dungeon/" + id)).andExpect(status().isOk());
    mvc.perform(get("/guild/dungeon/" + (id + 1000))).andExpect(status().isNotFound());
    String tavern = mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("へ向かっている途中")))
        .andExpect(content().string(containsString("pop-live")))
        .andReturn().getResponse().getContentAsString();
    // Out of the room, walking in the dungeon scene instead.
    String room = tavern.substring(tavern.indexOf("<section class=\"guild-room"), tavern.indexOf("<section class=\"guild-dungeon-scene"));
    String scene = tavern.substring(tavern.indexOf("<section class=\"guild-dungeon-scene"), tavern.indexOf("guild-caption"));
    for (long member : party) {
      assertThat(room).doesNotContain("/guild/characters/" + member + "\"");
      assertThat(scene).contains("/guild/characters/" + member + "\"");
    }
    for (String body : bodies) assertThat(tavern).doesNotContain(body);
    mvc.perform(post("/guild/maintenance/dungeon")).andExpect(status().is4xxClientError());
  }

  @Test void theWorldsRulesHoldForNewcomers() {
    assertThat(world.villages().keySet()).containsExactly("grantford", "elmwood", "tsukikage");
    assertThat(world.factions().get("tsukikage_samurai").village()).isEqualTo("tsukikage");
    assertThat(GuildWorld.faithName("精霊信仰", "spirit", "tsukikage")).isEqualTo("精霊信仰（八百万信仰）");
    assertThat(GuildWorld.faithName("精霊信仰", "spirit", "elmwood")).isEqualTo("精霊信仰");
    String samurai = DRAFT.replace("冒険者", "侍").replace("ヨーロッパ系", "日本系").replace("リネ", "灯子").replace("グランフォード", "月影郷")
        .replace("無所属", "月影武家衆").replace("無信仰", "精霊信仰");
    assertThat(generator.validate(samurai, OPEN)).satisfies(d -> {
      assertThat(d.village()).isEqualTo("tsukikage");
      assertThat(d.faction()).isEqualTo("tsukikage_samurai");
      assertThat(d.faith()).isEqualTo("spirit");
    });
    for (String bad : List.of(DRAFT.replace("グランフォード", "月影郷"), DRAFT.replace("無所属", "月影武家衆"),
        DRAFT.replace("冒険者", "侍"), samurai.replace("侍", "騎士"), DRAFT.replace("無信仰", "太陽教"),
        DRAFT.replace("\"village\":\"グランフォード\",", ""))) {
      assertThatThrownBy(() -> generator.validate(bad, OPEN)).isInstanceOf(IllegalArgumentException.class);
    }
    var noTsukikage = new GuildPeople.Options(OPEN.genders(), OPEN.occupations(), OPEN.lineages(), List.of("grantford"),
        OPEN.factions(), OPEN.faiths(), List.of());
    assertThatThrownBy(() -> generator.validate(samurai, noTsukikage)).hasMessage("Village not offered");
  }

  @Test void onlyCodeRulesMoveLabelsAndPartnersAreExclusive() {
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    long mira = resident("ミラ", 16, "ALIVE", 2);
    var now = HOUR.toOffsetDateTime();
    var warm = new GuildInteractionService.Rel(60, 80, 0, 0);
    var a = new GuildInteractionService.Person(rine, "リネ", 28, "女性", null, "");
    var b = new GuildInteractionService.Person(roan, "ロアン", 30, "男性", null, "");
    var minor = new GuildInteractionService.Person(mira, "ミラ", 16, "女性", null, "");
    var friends = new GuildInteractionService.Pair(a, b, warm, warm, "friend", now.minusDays(30), 5, 0, List.of(), null);
    assertThat(interactions.label(friends, warm, warm, "lover", 4, 0, now)).isEqualTo("lover");
    assertThat(interactions.label(friends, warm, warm, "spouse", 4, 0, now)).as("spouse needs lovers first").isEqualTo("friend");
    assertThat(interactions.label(friends, warm, warm, null, 4, 0, now)).as("no attempt, no romance").isEqualTo("friend");
    var withMinor = new GuildInteractionService.Pair(a, minor, warm, warm, "friend", now.minusDays(30), 5, 0, List.of(), null);
    assertThat(interactions.label(withMinor, warm, warm, "lover", 4, 0, now)).isEqualTo("friend");
    var newLovers = new GuildInteractionService.Pair(a, b, warm, warm, "lover", now.minusDays(2), 9, 0, List.of(), null);
    assertThat(interactions.label(newLovers, warm, warm, "spouse", 10, 0, now)).as("a week first").isEqualTo("lover");
    var lovers = new GuildInteractionService.Pair(a, b, warm, warm, "lover", now.minusDays(8), 9, 0, List.of(), null);
    assertThat(interactions.label(lovers, warm, warm, "spouse", 10, 0, now)).isEqualTo("spouse");
    var cold = new GuildInteractionService.Rel(0, -50, 70, 0);
    assertThat(interactions.label(friends, cold, cold, null, 4, 0, now)).isEqualTo("acquaintance");
    assertThat(interactions.label(friends, cold, cold, "rival", 4, 0, now)).isEqualTo("rival");
    jdbc.update("INSERT INTO T_GUILD_BOND (low_id,high_id,label,since) VALUES (?,?,'lover',?)", Math.min(rine, mira), Math.max(rine, mira), now);
    assertThat(interactions.label(friends, warm, warm, "lover", 4, 0, now)).as("one partner at a time").isEqualTo("friend");
  }

  @Test void theLogEntryAndPairPagesShowTheStoryWithoutPlayers() throws Exception {
    source();
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    // Like real births: history rows with no interaction, which the detail page must step over.
    for (long person : List.of(rine, roan)) {
      jdbc.update("INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id,kind,to_value,change_kind,reason) VALUES (?,'village','grantford','birth','記憶から')", person);
    }
    interactions.runHour(HOUR);
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_INTERACTION", Long.class);
    String summary = jdbc.queryForObject("SELECT summary FROM T_GUILD_INTERACTION", String.class);
    String pair = Math.min(rine, roan) + "-" + Math.max(rine, roan);
    mvc.perform(get("/guild/log")).andExpect(status().isOk())
        .andExpect(content().string(containsString(summary)))
        .andExpect(content().string(containsString("class=\"bookcase\"")))
        .andExpect(content().string(containsString("class=\"book spine-")))
        .andExpect(content().string(containsString("href=\"/guild/log\" aria-current=\"page\"")));
    mvc.perform(get("/guild/log").param("character", String.valueOf(roan))).andExpect(status().isOk())
        .andExpect(content().string(containsString("ロアン の交流")));
    mvc.perform(get("/guild/log/" + id)).andExpect(status().isOk())
        .andExpect(content().string(containsString("<p class=\"adv-narration\" data-adv-line>夕方の酒場。窓の外では雨が降っている。</p>")))
        // Each line as a bubble beside its speaker's figure: the first participant on the left, the second on the right.
        .andExpect(content().string(containsString("data-side=\"left\" data-name=\"リネ\"")))
        .andExpect(content().string(containsString("data-side=\"right\" data-name=\"ロアン\"")))
        .andExpect(content().string(containsString("<p>今日は冷えるね。</p>")))
        .andExpect(content().string(containsString("data-adv-start")))
        .andExpect(content().string(containsString("/guild/pairs/" + pair)))
        // The window opens on the monologue before anyone speaks, over the place it happened; the bubbles are folded below.
        .andExpect(content().string(containsString("<p class=\"adv-mono\">夕方の酒場。窓の外では雨が降っている。</p>")))
        .andExpect(content().string(containsString("adv-bg-guild")))
        .andExpect(content().string(containsString("data-place=\"guild\"")))
        .andExpect(content().string(containsString("会話を聞く")))
        .andExpect(content().string(containsString("<details class=\"adv-transcript\">")))
        .andExpect(content().string(containsString("知人になった")))
        .andExpect(content().string(not(containsString("PRIVATE_PLAYER_SECRET"))));
    mvc.perform(get("/guild/pairs/" + pair)).andExpect(status().isOk())
        .andExpect(content().string(containsString("の、出会いから今まで")))
        .andExpect(content().string(containsString(summary)));
    mvc.perform(get("/guild/pairs/" + Math.max(rine, roan) + "-" + Math.min(rine, roan))).andExpect(status().isNotFound());
    mvc.perform(get("/guild/log/999999")).andExpect(status().isNotFound());
    mvc.perform(get("/guild/characters/" + rine)).andExpect(status().isOk())
        .andExpect(content().string(containsString("この街での関係")))
        .andExpect(content().string(containsString(summary)));
    var scene = chronicle.scene(HOUR.plusMinutes(10), HOUR.toLocalDate());
    assertThat(scene.interactionId()).isEqualTo(id);
    assertThat(scene.lines().get(rine)).isEqualTo("「今日は冷えるね。」");
    assertThat(chronicle.scene(HOUR.minusHours(1), HOUR.toLocalDate())).isNull();
    mvc.perform(post("/guild/maintenance/interaction").with(user("member").roles("PLAYER")).with(csrf())).andExpect(status().isForbidden());
    mvc.perform(post("/guild/maintenance/interaction").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    // Deleting a resident takes their interactions and relationships with them.
    guild.delete(rine);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isZero();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION")).isZero();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_BOND")).isZero();
  }

  @Test void publicViewsAreReadOnlyAndAdminWritesNeedRoleAndCsrf() throws Exception {
    for (String path : List.of("/guild", "/guild/log", "/guild/dungeon", "/guild/towns/grantford")) {
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
    verify(bedrock, times(1)).generate(anyString(), argThat(prompt -> !prompt.contains("PRIVATE_PLAYER_SECRET") && !prompt.contains("765611")
        && prompt.contains("村 月影郷：") && prompt.contains("派閥の選択肢=") && prompt.contains("信仰の選択肢=暁光教、精霊信仰、無信仰")), eq(1200), any());
    assertThat(jdbc.queryForList("SELECT kind || ':' || to_value || ':' || change_kind FROM T_GUILD_AFFILIATION_HISTORY ORDER BY kind", String.class))
        .containsExactly("faction:none:birth", "faith:none:birth", "village:grantford:birth");
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).isEmpty();
    var character = guild.characters(0).getFirst();
    mvc.perform(get("/guild/characters/" + character.id())).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネ")))
        .andExpect(content().string(containsString("28歳・女性・冒険者")))
        .andExpect(content().string(containsString("記録から読み取られたこと：出入りの多い短い一日から")))
        .andExpect(content().string(not(containsString("系の名"))))
        .andExpect(content().string(containsString("<dt>出身地</dt><dd>グランフォード</dd>")))
        .andExpect(content().string(containsString("<dt>信仰</dt><dd>無信仰</dd>")))
        .andExpect(content().string(containsString("guild-people.svg?v=7#outfit-")))
        .andExpect(content().string(containsString("viewBox=\"0 0 96 144\"")))
        .andExpect(content().string(containsString("guild-people.svg?v=7#expression-thoughtful")))
        .andExpect(content().string(containsString("7 Days to Die・")))
        .andExpect(content().string(containsString("zombieGuild10を1体倒した日から生まれました")))
        .andExpect(content().string(containsString("10:00 古い家でzombieGuild10を倒した")))
        .andExpect(content().string(not(containsString("PRIVATE_WORLD"))))
        .andExpect(content().string(not(containsString("PRIVATE_PLAYER_SECRET"))))
        .andExpect(content().string(not(containsString("765611"))))
        .andExpect(content().string(not(containsString("source_key"))));
    mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("slot-0 ")))
        .andExpect(content().string(containsString("#outfit-")))
        .andExpect(content().string(containsString("class=\"guild-link active\"")))
        .andExpect(content().string(not(containsString("<dialog id=\"guild-window\" class=\"guild-window\" open"))));
  }

  @Test void windowsOpenOverTheTavernWithTheirTab() throws Exception {
    source();
    guild.generateToday();
    guild.refreshVisits();
    var character = guild.characters(0).getFirst();
    mvc.perform(get("/guild/log")).andExpect(status().isOk())
        .andExpect(content().string(containsString("guild-room")))
        .andExpect(content().string(containsString("open=\"open\"")))
        .andExpect(content().string(containsString("href=\"/guild/log\" aria-current=\"page\"")));
    // A resident's window opens from the tavern itself; there is no roster to list everyone.
    mvc.perform(get("/guild/characters/" + character.id())).andExpect(status().isOk())
        .andExpect(content().string(containsString("<title>リネ | Rubinco</title>")))
        .andExpect(content().string(containsString("href=\"/guild\" aria-current=\"page\"")));
    // The tabs are the tavern, the dungeon and the log; the visitors, roster and board windows are gone.
    for (String gone : List.of("/guild/visitors", "/guild/board", "/guild/characters")) {
      mvc.perform(get(gone).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
      mvc.perform(get("/guild")).andExpect(content().string(not(containsString("href=\"" + gone + "\""))));
    }
    mvc.perform(get("/guild")).andExpect(status().isOk())
        .andExpect(content().string(not(containsString("/guild/maintenance\"><svg"))));
  }

  @Test void invalidOutputIsNotSavedAndRetryIsBounded() {
    source();
    when(bedrock.generate(anyString(), anyString(), anyInt(), any())).thenReturn("{\"name\":\"bad\"}");
    guild.generateToday();
    assertThat(guild.jobs().getFirst().status()).isEqualTo("FAILED");
    assertThat(guild.characters(0)).isEmpty();
    assertThat(guild.jobs().getFirst().message()).contains("AIの応答が形式に合いませんでした");
    when(bedrock.generate(anyString(), anyString(), anyInt(), any())).thenThrow(new IllegalStateException("PRIVATE_RESPONSE"));
    // daily-limit 2 in this test, plus 5 failures: 7 attempts in all, then no more calls.
    for (int i = 0; i < 8; i++) guild.generateToday();
    assertThat(guild.jobs().getFirst().attempts()).isEqualTo(7);
    assertThat(guild.jobs().getFirst().message()).doesNotContain("PRIVATE_RESPONSE");
    assertThat(guild.jobs().getFirst().message()).contains("Bedrockの呼び出しに失敗", "IllegalStateException");
    verify(bedrock, times(7)).generate(anyString(), anyString(), anyInt(), any());
  }

  @Test void failedGenerationCanRecoverWithoutLosingExistingPeople() {
    source();
    when(bedrock.generate(anyString(), anyString(), anyInt(), any())).thenReturn("invalid").thenReturn(DRAFT);
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
    worldEvent(yesterday.plusMinutes(1), "AIR_DROP", "air");
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).isEmpty();
    kill(yesterday.plusMinutes(2), "zombieGuild", null, null, "yesterday");
    // A zombie killing an animal is not a player's memory.
    jdbc.update("INSERT INTO T_ENTITY_KILL_TRANSACTION (occurred_at,player_name,player_entity_id,target_entity_type,target_entity_id,source_file,source_log_hash) "
        + "VALUES (?,'zombieBoe',9,'animalRabbit',2,'guild-test','guild-test-zombie')", yesterday);
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).hasValueSatisfying(source -> {
      assertThat(source.game()).isEqualTo("7dtd");
      assertThat(source.category()).isEqualTo("hunt");
      assertThat(source.label()).startsWith("7 Days to Die・").endsWith("zombieGuildを1体倒した日");
      assertThat(source.facts()).isEqualTo("21:07 zombieGuildを倒した");
      assertThat(source.key() + source.facts() + source.label()).doesNotContain("PRIVATE");
    });
    jdbc.update("UPDATE T_ENTITY_KILL_TRANSACTION SET occurred_at = ? WHERE source_file='guild-test'", OffsetDateTime.now());
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).isEmpty();
  }

  @Test void aDayOfOneEnemyGivesItsFirstMiddleAndLastKill() {
    var day = LocalDate.now(GuildService.TOKYO).minusDays(3);
    for (int minute = 0; minute < 30; minute++) {
      kill(day.atTime(13, minute).atZone(GuildService.TOKYO).toOffsetDateTime(), "zombieGuild", null, null, "k-" + minute);
    }
    var source = sources.choose(LocalDate.now(GuildService.TOKYO)).source().orElseThrow();
    assertThat(source.facts()).isEqualTo("13:00 zombieGuildを倒した\n13:15 zombieGuildを倒した\n13:29 zombieGuildを倒した");
    assertThat(source.label()).endsWith("zombieGuildを30体倒した日");
  }

  private void worldEvent(OffsetDateTime at, String type, String hash) {
    jdbc.update("INSERT INTO T_WORLD_EVENT_TRANSACTION (occurred_at,event_type,actor_player_name,source_file,source_log_hash,raw_line) "
        + "VALUES (?,?,'PRIVATE_NAME','guild-test',?,'PRIVATE_RAW')", at, type, "guild-test-" + hash);
  }

  private void kill(OffsetDateTime at, String type, Integer x, Integer z, String hash) {
    jdbc.update("INSERT INTO T_ENTITY_KILL_TRANSACTION (occurred_at,player_name,player_entity_id,target_entity_type,target_entity_id,source_file,source_log_hash,"
        + "player_position_x,player_position_z) VALUES (?,'PRIVATE_NAME',1,?,2,'guild-test',?,?,?)", at, type, "guild-test-" + hash, x, z);
  }

  @Test void aCategoryAtItsCapIsLeftOutUntilOthersArrive() throws Exception {
    // Three recent residents (the cap) were born from enemies defeated.
    for (int i = 1; i <= 3; i++) {
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance) VALUES (?,'7dtd',?,'紹介','出自','性格','moss')",
          LocalDate.now(GuildService.TOKYO).minusDays(i), "テスト" + "アイウ".charAt(i - 1));
      long id = jdbc.queryForObject("SELECT MAX(id) FROM M_GUILD_CHARACTER", Long.class);
      jdbc.update("INSERT INTO T_GUILD_SOURCE (character_id,source_key,facts,memory_label,category) VALUES (?,?,'x','x','hunt')", id, "memory:test:" + i);
    }
    source();
    var choice = sources.choose(LocalDate.now(GuildService.TOKYO));
    assertThat(choice.source()).isEmpty();
    assertThat(choice.skipped()).contains("倒したもの").contains("偏り");
    assertThat(guild.generateToday()).contains("偏り");
    verifyNoInteractions(bedrock);
    mvc.perform(get("/guild/maintenance").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
        .andExpect(content().string(containsString("直近の住人（新しい順）: 倒したもの、倒したもの、倒したもの")))
        .andExpect(content().string(containsString("<td>7 Days to Die</td><td>倒したもの</td><td>1</td>")));
    // A place reached on foot is still welcome.
    walk(LocalDate.now(GuildService.TOKYO).minusDays(2).atTime(13, 0).atZone(GuildService.TOKYO).toOffsetDateTime(), 1000, 2000, "WALK", "other");
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).hasValueSatisfying(source ->
        assertThat(source.category()).isEqualTo("place"));
  }

  @Test void theAiChoosesOnlyWhatIsOfferedAndCrowdedChoicesAreLeftOut() {
    assertThat(people.options()).satisfies(options -> {
      assertThat(options.genders()).containsExactly("女性", "男性");
      assertThat(options.occupations()).contains("鍛冶屋", "漁師", "騎士");
      assertThat(options.recent()).isEmpty();
    });
    // Seven women, three blacksmiths and five of European names among the last ten: none is offered again.
    for (int i = 0; i < 10; i++) {
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance,gender,occupation,name_lineage) "
          + "VALUES (?,'7dtd',?,'旅の者。','記録から。','穏やか。','moss',?,?,?)", LocalDate.now(GuildService.TOKYO).minusDays(200 + i),
          "住人" + (char) ('ア' + i * 2), i < 7 ? "女性" : "男性", i < 3 ? "鍛冶屋" : "農家", i < 5 ? "european" : i < 8 ? "japanese" : "north");
    }
    var crowded = people.options();
    assertThat(crowded.genders()).containsExactly("男性");
    assertThat(crowded.occupations()).doesNotContain("鍛冶屋", "農家").contains("漁師", "騎士");
    assertThat(crowded.lineages()).doesNotContain(GuildPeople.Lineage.EUROPEAN).contains(GuildPeople.Lineage.JAPANESE);
    assertThat(crowded.recent()).hasSize(5).allMatch(line -> line.matches(".+（(女性|男性)・(鍛冶屋|農家)・(日本系|北方系)・グランフォード・無所属）"));
    // Ten from Grantford: it is not offered, and the Japanese lineage comes only with Tsukikage.
    assertThat(crowded.villages()).containsExactly("elmwood", "tsukikage");
    assertThat(crowded.occupations()).contains("侍", "巫女");
    assertThat(crowded.factions()).endsWith("none");
    assertThatThrownBy(() -> generator.validate(DRAFT, crowded)).hasMessage("Gender not offered");
    var draft = generator.validate(DRAFT, OPEN);
    assertThat(draft).satisfies(d -> {
      assertThat(d.gender()).isEqualTo("女性");
      assertThat(d.occupation()).isEqualTo("冒険者");
      assertThat(d.lineage()).isEqualTo(GuildPeople.Lineage.EUROPEAN);
      assertThat(d.reading()).startsWith("出入りの多い");
    });
    for (int i = 0; i < 40; i++) {
      // Women wear the women's cut; outfits without their own legs get a skirt.
      var look = people.look("女性", i % 2 == 0 ? "騎士" : "鍛冶屋");
      assertThat(look.outfit()).isIn("knight_f", "crafter_f");
      assertThat(look.layers()).startsWith("body-core").contains("outfit-" + look.outfit());
      assertThat(look.style()).matches("(--[a-z0-9]+:#[0-9a-f]{6};?)+");
    }
    assertThat(GuildPeople.Look.of("knight", "short", "#000000", "#ffffff", "#123456", "#654321", "男性").layers())
        .containsExactly("body-core", "head-soft", "eyes-round", "face-m", "brows-natural", "outfit-knight", "hair-short", "hat-knight");
    assertThat(GuildPeople.Look.of("cook_f", "bob", "#000000", "#ffffff", "#123456", "#654321", "女性", "ribbon", "#3a5a8a").layers())
        .containsExactly("body-core", "head-soft", "eyes-round", "face-f", "brows-natural", "outfit-cook_f", "hair-bob", "acc-ribbon", "hat-cook_f");
    assertThat(GuildPeople.Look.of("adventurer", "short", "#000000", "#ffffff", "#123456", "#654321", "男性", "glasses", "#3a5a8a").style())
        .endsWith(";--eye:#3a5a8a");
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
      when(bedrock.generate(anyString(), anyString(), anyInt(), any())).thenReturn(DRAFT.replace("リネ", "ミナト"));
      assertThat(guild.generateToday()).contains("名前と重なりました");
    } finally {
      jdbc.update("DELETE FROM M_PLAYER WHERE player_name = 'ミナトリネ'");
    }
  }

  @Test void administratorsAddUpToTheDailyLimitRedrawVisitorsAndDelete() throws Exception {
    sourceAt(1); sourceAt(10); sourceAt(19);
    when(bedrock.generate(anyString(), anyString(), anyInt(), any()))
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
        .andExpect(content().string(containsString("7 Days to Die・")));

    long id = guild.characters(0).getFirst().id();
    mvc.perform(post("/guild/maintenance/characters/" + id + "/delete").with(user("member").roles("PLAYER")).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/guild/maintenance/characters/" + id + "/delete").with(admin).with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(guild.characters(0)).hasSize(1);
    assertThat(guild.room(ZonedDateTime.now()).visits()).hasSize(1);
    mvc.perform(get("/guild/characters/" + id)).andExpect(status().isNotFound());
  }

  @Test void maintenanceRedrawChangesOnlyAppearanceAndNeedsAdminWithCsrf() throws Exception {
    source();
    guild.generateToday();
    long id = guild.characters(0).getFirst().id();
    String path = "/guild/maintenance/characters/" + id + "/appearance";
    var admin = user("admin").roles("ADMIN");
    var before = jdbc.queryForMap("SELECT * FROM M_GUILD_CHARACTER WHERE id = ?", id);
    var history = new java.util.HashMap<String, Object>();
    for (String table : List.of("T_GUILD_SOURCE", "T_GUILD_VISIT", "T_GUILD_TALK", "T_GUILD_THOUGHT", "T_GUILD_RELATION", "T_GUILD_AFFILIATION_HISTORY")) {
      history.put(table, jdbc.queryForList("SELECT * FROM " + table));
    }
    clearInvocations(bedrock);
    mvc.perform(post(path).with(user("member").roles("PLAYER")).with(csrf())).andExpect(status().isForbidden());
    mvc.perform(post(path).with(admin)).andExpect(status().isForbidden());
    mvc.perform(post(path).with(admin).with(csrf()).param("mode", "invalid")).andExpect(status().isBadRequest());
    assertThat(jdbc.queryForMap("SELECT * FROM M_GUILD_CHARACTER WHERE id = ?", id)).isEqualTo(before);
    mvc.perform(post(path).with(admin).with(csrf()).param("mode", "eyes"))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/guild/maintenance#resident-" + id));
    var eyes = jdbc.queryForMap("SELECT * FROM M_GUILD_CHARACTER WHERE id = ?", id);
    assertThat(eyes.get("eye_style")).isNotEqualTo(before.get("eye_style"));
    assertThat(eyes.get("brow_style")).isNotEqualTo(before.get("brow_style"));
    for (String key : before.keySet()) {
      if (!List.of("eye_style", "brow_style").contains(key.toLowerCase(java.util.Locale.ROOT))) {
        assertThat(eyes.get(key)).as(key).isEqualTo(before.get(key));
      }
    }
    mvc.perform(post(path).with(admin).with(csrf()).param("mode", "all")).andExpect(status().is3xxRedirection());
    var all = jdbc.queryForMap("SELECT * FROM M_GUILD_CHARACTER WHERE id = ?", id);
    var visual = java.util.Set.of("outfit", "hair_style", "hair_color", "cloth_color", "cloth2_color", "accessory", "eye_color", "eye_style", "brow_style", "outfit_variant");
    for (String key : before.keySet()) {
      if (!visual.contains(key.toLowerCase(java.util.Locale.ROOT))) assertThat(all.get(key)).as(key).isEqualTo(before.get(key));
    }
    var saved = guild.character(id).look();
    assertThat(saved).isNotNull();
    assertThat(saved.hair()).isNotEqualTo(eyes.get("hair_style"));
    assertThat(saved.eyeStyle()).isNotEqualTo(eyes.get("eye_style"));
    assertThat(guild.character(id).look()).isEqualTo(saved);
    for (String table : history.keySet()) assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).isEqualTo(history.get(table));
    verifyNoInteractions(bedrock);
    mvc.perform(post("/guild/maintenance/characters/999999/appearance").with(admin).with(csrf())).andExpect(status().isNotFound());
    var response = mvc.perform(get("/guild/maintenance").with(admin)).andExpect(status().isOk())
        .andExpect(content().string(containsString("目元を引き直す")))
        .andExpect(content().string(containsString("外見を引き直す")))
        .andExpect(content().string(containsString("guild-people.svg?v=7#eyes-")))
        .andExpect(content().string(containsString("name=\"_csrf\"")))
        .andReturn().getResponse();
    var preview = java.nio.file.Path.of("target/guild-preview/maintenance.html");
    java.nio.file.Files.createDirectories(preview.getParent());
    java.nio.file.Files.writeString(preview, response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test void legacyResidentCanReceiveALookWithoutChangingTheirUnknownProfile() {
    jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance) VALUES (?, 'terraria', '旧住人', '紹介', '由来', '穏やか', 'moss')",
        LocalDate.now(GuildService.TOKYO));
    var old = guild.characters(0).getFirst();
    assertThat(old.look()).isNull();
    guild.redrawAppearance(old.id(), true);
    var redrawn = guild.character(old.id());
    assertThat(redrawn.look()).isNotNull();
    assertThat(redrawn.gender()).isNull();
    assertThat(redrawn.occupation()).isNull();
    assertThat(redrawn.name()).isEqualTo(old.name());
    assertThat(redrawn.personality()).isEqualTo(old.personality());
  }

  @Test void savedRosterDoesNotChangeAfterBirthAndRenderPreviewFixtures() throws Exception {
    // Explicitly synthetic preview residents, never inserted in a real application database.
    List<String> jobs = List.of("騎士", "料理人", "侍", "巫女", "商人", "猟師", "聖職者", "冒険者", "浪人", "農家");
    for (int i = 1; i <= 10; i++) {
      String gender = i % 2 == 0 ? "女性" : "男性";
      var look = people.look(gender, jobs.get(i - 1));
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance,gender,occupation,outfit,"
          + "hair_style,hair_color,skin_color,cloth_color,cloth2_color,accessory,eye_color,eye_style,brow_style,outfit_variant) VALUES (?,'terraria',?,'小さな街で暮らす職人。今日は道具の手入れをしています。',"
          + "'表示確認用の架空データです。','静かな話し方。手仕事が好きで、方向音痴。',?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          LocalDate.now(GuildService.TOKYO).minusDays(i), List.of("ロアン", "ミナ", "エルド", "トト", "シエラ", "カイ", "ノエル", "ユナ", "ガロ", "リタ").get(i - 1),
          List.of("ember", "moss", "violet").get(i % 3), gender, jobs.get(i - 1), look.outfit(), look.hair(), look.hairColor(), look.skin(),
          look.cloth(), look.cloth2(), look.accessory(), look.eye(), look.eyeStyle(), look.browStyle(), look.outfitVariant());
    }
    // Fifteen more, five living in each nation, so the nations' taverns have someone in them too.
    for (int i = 0; i < 15; i++) {
      String nation = List.of("grantford", "elmwood", "tsukikage").get(i % 3);
      String gender = i % 2 == 0 ? "男性" : "女性";
      var look = people.look(gender, jobs.get(i % jobs.size()));
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance,gender,occupation,outfit,"
          + "hair_style,hair_color,skin_color,cloth_color,cloth2_color,accessory,eye_color,home_village,current_village,eye_style,brow_style,outfit_variant) VALUES (?,'terraria',?,'国で暮らす住人。',"
          + "'表示確認用の架空データです。','穏やか。',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          LocalDate.now(GuildService.TOKYO).minusDays(40 + i), "見本" + (i + 1), List.of("ember", "moss", "violet").get(i % 3), gender,
          jobs.get(i % jobs.size()), look.outfit(), look.hair(), look.hairColor(), look.skin(), look.cloth(), look.cloth2(), look.accessory(), look.eye(),
          nation, nation, look.eyeStyle(), look.browStyle(), look.outfitVariant());
    }
    guild.prepareToday();
    var before = guild.room(ZonedDateTime.now());
    source(); guild.generateToday();
    assertThat(guild.room(ZonedDateTime.now())).isEqualTo(before);
    assertThat(before.visits()).hasSize(10);
    var root = java.nio.file.Path.of("target/guild-preview");
    java.nio.file.Files.createDirectories(root.resolve("guild/characters"));
    java.nio.file.Files.createDirectories(root.resolve("towns"));
    for (String path : List.of("/guild", "/guild/log", "/guild/dungeon", "/guild/towns/grantford", "/guild/towns/elmwood",
        "/guild/towns/tsukikage")) {
      var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
      String html = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replace("<main class=", "<p style=\"text-align:center;color:#f0d8c2\">表示確認用サンプル・実プレイ由来ではありません</p><main class=");
      java.nio.file.Files.writeString(root.resolve(path.equals("/guild") ? "index.html" : path.substring(7) + ".html"), html);
    }
    for (var person : guild.characters(0)) {
      var response = mvc.perform(get("/guild/characters/" + person.id())).andExpect(status().isOk()).andReturn().getResponse();
      java.nio.file.Files.writeString(root.resolve("guild/characters/" + person.id()), response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    // A run that left 25 minutes ago: two stages out, two to come, its party out of the tavern.
    dungeon.depart(ZonedDateTime.now(GuildService.TOKYO));
    var left = OffsetDateTime.now().minusMinutes(25);
    jdbc.update("UPDATE T_GUILD_EXPEDITION SET departed_at = ?, returned_at = ?", left, left.plusMinutes(40));
    for (int stage = 1; stage <= 4; stage++) {
      jdbc.update("UPDATE T_GUILD_EXPEDITION_STAGE SET reveal_at = ? WHERE stage = ?", left.plusMinutes(10L * stage), stage);
    }
    for (String[] page : new String[][] {{"/guild/dungeon", "dungeon.html"}, {"/guild", "index-dungeon.html"}}) {
      var response = mvc.perform(get(page[0])).andExpect(status().isOk()).andReturn().getResponse();
      java.nio.file.Files.writeString(root.resolve(page[1]), response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test void claudeStyleOutputIsAccepted() {
    // A code fence, a lead-in sentence, short numbers, line breaks and an extra key are all fine.
    String fenced = "人物を作りました。\n```json\n" + DRAFT.replace("小さな店", "3年後に小さな店").replace("節約家", "\\n節約家")
        .replace("\"personality\"", "\"note\":\"x\",\"personality\"") + "\n```";
    var draft = generator.validate(fenced, OPEN);
    assertThat(draft.introduction()).contains("3年後に小さな店");
    assertThat(draft.personality()).contains("目がない").doesNotContain("\n");
    assertThat(draft.age()).isEqualTo(28);
  }

  @Test void schemaRejectsUnsafeOrUnknownContent() {
    for (String bad : List.of(DRAFT.replace("28", "200"), DRAFT.replace("\"age\":28,", ""), DRAFT.replace("リネ", "<script>"),
        DRAFT.replace("リネ", "https://secret"), DRAFT.replace("リネ", "76561123456"), DRAFT.replace("リネ", "凛"),
        DRAFT.replace("リネ", "リネリネリネリネリ"), DRAFT.replace("\"リネ\"", "null"), DRAFT.replace("\"女性\"", "\"その他\""),
        DRAFT.replace("冒険者", "魔王"), DRAFT.replace("ヨーロッパ系", "日本系"), DRAFT.replace("ヨーロッパ系", "月の民"),
        DRAFT.replace("\"reading\":\"出入りの多い短い一日から、身軽に動き回る暮らしが見えた。\",", ""))) {
      assertThatThrownBy(() -> generator.validate(bad, OPEN)).isInstanceOf(RuntimeException.class);
    }
  }
}
