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
  @Autowired GuildPeople people;
  @Autowired GuildInteractionService interactions;
  @Autowired GuildInteractionWriter writer;
  @Autowired GuildChronicle chronicle;
  @Autowired GuildDossier dossier;
  @Autowired GuildWorld world;
  @Autowired GuildProperties properties;
  @Autowired JdbcTemplate jdbc;
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
    for (String table : List.of("T_GUILD_AFFILIATION_HISTORY", "T_GUILD_RELATION_EVENT", "T_GUILD_THREAD", "T_GUILD_INTERACTION_PARTICIPANT", "T_GUILD_INTERACTION",
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
    when(bedrock.generate(anyString(), anyString(), anyInt(), anyFloat(), any())).thenReturn(DRAFT);
    when(bedrock.generate(anyString(), anyString(), eq(700), anyFloat(), any())).thenReturn(SCENE);
  }

  static final String SCENE = """
      {"narration":"夕方の酒場。窓の外では雨が降っている。","lines":[{"speaker":"リネ","text":"今日は冷えるね。"},
       {"speaker":"ロアン","text":"火のそばに座ろう。"}]}
      """;
  /** Today at 20:00 JST, one of the three daily slots: the slot every interaction test runs in. */
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
    assertThat(interactions.runHour(HOUR.plusMinutes(30))).contains("すでに処理済み");
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_HOURLY_JOB WHERE status = 'SUCCEEDED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION_PARTICIPANT")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_RELATION WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)", rine, roan, roan, rine)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT label FROM T_GUILD_BOND", String.class)).isEqualTo("acquaintance");
    assertThat(jdbc.queryForObject("SELECT written_by FROM T_GUILD_INTERACTION", String.class)).isEqualTo("ai");
    verify(bedrock, times(1)).generate(anyString(), argThat(input -> input.contains("リネ") && input.contains("ロアン")), eq(700), anyFloat(), any());
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
      for (int hour = 0; hour < 24; hour++) interactions.runHour(date.atTime(hour, 0).atZone(GuildService.TOKYO));
    }
    // Only the three slots run; at most three interactions a day.
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_HOURLY_JOB")).isEqualTo(60 * 3);
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_INTERACTION")).isGreaterThan(60 * 2);
    assertThat(jdbc.queryForList("SELECT DISTINCT SUBSTRING(slot_key, 12, 2) FROM T_GUILD_HOURLY_JOB ORDER BY 1", String.class))
        .containsExactly("20", "21", "22");
    assertThat(count("SELECT MAX(n) FROM (SELECT COUNT(*) n FROM T_GUILD_INTERACTION GROUP BY SUBSTRING(slot_key, 1, 10)) t")).isLessThanOrEqualTo(3);
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
    verify(bedrock, never()).generate(anyString(), anyString(), eq(700), anyFloat(), any());
  }

  @Test void aFailedWriterFallsBackToTheTemplateWithTheSameOutcome() {
    resident("リネ", 28, "ALIVE", 0);
    resident("ロアン", 30, "ALIVE", 1);
    when(bedrock.generate(anyString(), anyString(), eq(700), anyFloat(), any())).thenThrow(new IllegalStateException("down"));
    assertThat(interactions.runHour(HOUR)).doesNotContain("失敗");
    assertThat(jdbc.queryForObject("SELECT written_by FROM T_GUILD_INTERACTION", String.class)).isEqualTo("template");
    String body = jdbc.queryForObject("SELECT body FROM T_GUILD_INTERACTION", String.class);
    assertThat(body).isNotBlank().doesNotContain("{a}").doesNotContain("{b}");
  }

  @Test void writerAcceptsOnlyParticipantsAndChangesCodeMade() {
    var a = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか");
    var b = new GuildInteractionService.Person(2, "ロアン", 30, "男性", "鍛冶屋", "無口");
    var scene = new GuildInteractionWriter.Scene("evening", "雑談", "二人は天気の話をした", a, b, "ふつう", "ふつう",
        "知人", null, "", List.of(), null, null, null);
    assertThat(writer.validate(SCENE, scene)).startsWith("夕方の酒場。").contains("リネ「今日は冷えるね。」");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("ロアン", "ガルド"), scene)).isInstanceOf(IllegalArgumentException.class);
    String married = SCENE.replace("火のそばに座ろう。", "俺たち、結婚しよう。いや結婚した。夫婦になった。");
    assertThatThrownBy(() -> writer.validate(married, scene)).hasMessageContaining("Unconfirmed");
    var wedding = new GuildInteractionWriter.Scene("evening", "求婚", "二人は夫婦になった", a, b, "深い愛情", "深い愛情",
        "恋人", "夫婦", "", List.of(), null, null, null);
    assertThat(writer.validate(married, wedding)).contains("夫婦になった");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("今日は冷えるね。", "彼は亡くなったよ。"), scene)).hasMessageContaining("Unconfirmed");
    // Joining a faction or a conversion only when code made it here, or when one of them went through it before.
    String joined = SCENE.replace("今日は冷えるね。", "騎士団への加入、おめでとう。");
    assertThatThrownBy(() -> writer.validate(joined, scene)).hasMessageContaining("Unconfirmed");
    var invite = new GuildInteractionWriter.Scene("evening", "勧誘", "リネは白銀の騎士団に加わった", a, b, "ふつう", "ふつう",
        "知人", null, "", List.of(), null, null, null, "派閥 白銀の騎士団：正義と名誉を重んじる騎士団。",
        "リネが白銀の騎士団に加わる（加入）", "join");
    assertThat(writer.validate(joined, invite)).contains("加入");
    assertThatThrownBy(() -> writer.validate(SCENE.replace("今日は冷えるね。", "改宗したんだ。"), invite)).hasMessageContaining("Unconfirmed");
    var convert = new GuildInteractionService.Affiliation("grantford", "grantford", "none", "dawnlight", "グランフォード", "無所属", "暁光教",
        null, null, java.util.Set.of("convert"), List.of("10月1日 信仰：無信仰 → 暁光教（改宗）：ロアンに導かれた"));
    var changed = new GuildInteractionService.Person(1, "リネ", 28, "女性", "冒険者", "穏やか", convert);
    var later = new GuildInteractionWriter.Scene("evening", "雑談", "二人は天気の話をした", changed, b, "ふつう", "ふつう",
        "知人", null, "", List.of(), null, null, null);
    assertThat(writer.validate(SCENE.replace("今日は冷えるね。", "改宗して半月になる。"), later)).contains("改宗");
  }

  /** Event cards that change a faction and a faith: code applies both, keeps the history and shows it. */
  @Test void eventsChangeFactionsAndFaithsAndKeepTheHistory() throws Exception {
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    jdbc.update("UPDATE M_GUILD_CHARACTER SET occupation = '騎士', faction = 'silver_knights', faith = 'dawnlight' WHERE id = ?", roan);
    var only = new GuildCards(new org.springframework.core.io.ClassPathResource("guild/affiliation-test-cards.yml"));
    var service = new GuildInteractionService(jdbc, properties, only, writer, world);
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
        && input.contains("派閥 白銀の騎士団：") && !input.contains("月影郷") && input.contains("この場面で起きる所属の変化=リネが")), eq(700), anyFloat(), any());
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
        .andExpect(content().string(containsString("毎日 20時・21時・22時")));
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
      assertThat(GuildInteractionService.attraction(gain, mixed, cool).get("affinity")).isIn(0, 2);
    }
    assertThat(GuildInteractionService.attraction(gain, mixed, new GuildInteractionService.Rel(0, 0, 0, 0, true))).containsEntry("affinity", 7).containsEntry("trust", 2);
    assertThat(GuildInteractionService.attraction(gain, mixed, new GuildInteractionService.Rel(0, 30, 0, 0))).containsEntry("affinity", 4);
    assertThat(GuildInteractionService.attraction(java.util.Map.of("affinity", -3), mixed, cool)).containsEntry("affinity", -3);
  }

  /** Interactions run in the evening, so every card must be able to fall then; gender and taste cards are read. */
  @Test void everyCardCanFallInTheEvening() {
    var cards = new GuildCards().cards();
    assertThat(cards).hasSizeGreaterThan(70);
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
    new GuildInteractionService(jdbc, properties, quiet, writer, world).runHour(HOUR);
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", rine, roan)).isEqualTo(45 + 2 + 1 + 1);
    assertThat(count("SELECT affinity FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?", roan, rine)).isBetween(0, 1);
    assertThat(jdbc.queryForMap("SELECT kind, a_id, summary FROM T_GUILD_THREAD")).containsEntry("KIND", "crush")
        .containsEntry("A_ID", rine).containsEntry("SUMMARY", "リネはロアンに片思いしている");
    verify(bedrock).generate(anyString(), argThat(input -> input.contains("二人は異性") && input.contains("好みのタイプで気になっている")
        && input.contains("この場面で生まれること=リネはロアンに片思いしている")), eq(700), anyFloat(), any());
    mvc.perform(get("/guild/characters/" + rine)).andExpect(status().isOk()).andExpect(content().string(containsString("気になっている")));
    mvc.perform(get("/guild/characters/" + roan)).andExpect(status().isOk()).andExpect(content().string(not(containsString("気になっている"))));
    // The log says who is who.
    mvc.perform(get("/guild/log")).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネ（28歳・女性・冒険者・グランフォード） × ロアン（30歳・男性・冒険者・グランフォード）")));
  }

  @Test void theTavernSeatsTen() throws Exception {
    for (int i = 0; i < 12; i++) {
      jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance) "
          + "VALUES (?,'7dtd',?,'旅の者。','記録から。','穏やか。','moss')", LocalDate.now(GuildService.TOKYO).minusDays(300 + i), "客" + (char) ('ア' + i * 2));
    }
    guild.prepareToday();
    assertThat(count("SELECT COUNT(*) FROM T_GUILD_VISIT")).isEqualTo(10);
    mvc.perform(get("/guild")).andExpect(status().isOk()).andExpect(content().string(containsString("seat-9 ")))
        .andExpect(content().string(containsString("table-four")));
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
    assertThat(interactions.label(friends, warm, warm, "lover", 6, 0, now)).isEqualTo("lover");
    assertThat(interactions.label(friends, warm, warm, "spouse", 6, 0, now)).as("spouse needs lovers first").isEqualTo("friend");
    assertThat(interactions.label(friends, warm, warm, null, 6, 0, now)).as("no attempt, no romance").isEqualTo("friend");
    var withMinor = new GuildInteractionService.Pair(a, minor, warm, warm, "friend", now.minusDays(30), 5, 0, List.of(), null);
    assertThat(interactions.label(withMinor, warm, warm, "lover", 6, 0, now)).isEqualTo("friend");
    var newLovers = new GuildInteractionService.Pair(a, b, warm, warm, "lover", now.minusDays(2), 9, 0, List.of(), null);
    assertThat(interactions.label(newLovers, warm, warm, "spouse", 10, 0, now)).as("a week first").isEqualTo("lover");
    var lovers = new GuildInteractionService.Pair(a, b, warm, warm, "lover", now.minusDays(8), 9, 0, List.of(), null);
    assertThat(interactions.label(lovers, warm, warm, "spouse", 10, 0, now)).isEqualTo("spouse");
    var cold = new GuildInteractionService.Rel(0, -50, 70, 0);
    assertThat(interactions.label(friends, cold, cold, null, 6, 0, now)).isEqualTo("acquaintance");
    assertThat(interactions.label(friends, cold, cold, "rival", 6, 0, now)).isEqualTo("rival");
    jdbc.update("INSERT INTO T_GUILD_BOND (low_id,high_id,label,since) VALUES (?,?,'lover',?)", Math.min(rine, mira), Math.max(rine, mira), now);
    assertThat(interactions.label(friends, warm, warm, "lover", 6, 0, now)).as("one partner at a time").isEqualTo("friend");
  }

  @Test void theLogEntryAndPairPagesShowTheStoryWithoutPlayers() throws Exception {
    source();
    long rine = resident("リネ", 28, "ALIVE", 0);
    long roan = resident("ロアン", 30, "ALIVE", 1);
    interactions.runHour(HOUR);
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_INTERACTION", Long.class);
    String summary = jdbc.queryForObject("SELECT summary FROM T_GUILD_INTERACTION", String.class);
    String pair = Math.min(rine, roan) + "-" + Math.max(rine, roan);
    mvc.perform(get("/guild/log")).andExpect(status().isOk())
        .andExpect(content().string(containsString(summary)))
        .andExpect(content().string(containsString("/guild/pairs/" + pair)))
        .andExpect(content().string(containsString("href=\"/guild/log\" aria-current=\"page\"")));
    mvc.perform(get("/guild/log").param("character", String.valueOf(roan))).andExpect(status().isOk())
        .andExpect(content().string(containsString("ロアン の交流")));
    mvc.perform(get("/guild/log/" + id)).andExpect(status().isOk())
        .andExpect(content().string(containsString("夕方の酒場。")))
        .andExpect(content().string(containsString("リネ「今日は冷えるね。」")))
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
    mvc.perform(get("/guild/board")).andExpect(status().isOk()).andExpect(content().string(containsString(summary)));
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
    verify(bedrock, times(1)).generate(anyString(), argThat(prompt -> !prompt.contains("PRIVATE_PLAYER_SECRET") && !prompt.contains("765611")
        && prompt.contains("村 月影郷：") && prompt.contains("派閥の選択肢=") && prompt.contains("信仰の選択肢=暁光教、精霊信仰、無信仰")), eq(1200), anyFloat(), any());
    assertThat(jdbc.queryForList("SELECT kind || ':' || to_value || ':' || change_kind FROM T_GUILD_AFFILIATION_HISTORY ORDER BY kind", String.class))
        .containsExactly("faction:none:birth", "faith:none:birth", "village:grantford:birth");
    assertThat(sources.choose(LocalDate.now(GuildService.TOKYO)).source()).isEmpty();
    var character = guild.characters(0).getFirst();
    mvc.perform(get("/guild/characters/" + character.id())).andExpect(status().isOk())
        .andExpect(content().string(containsString("リネ")))
        .andExpect(content().string(containsString("28歳・女性・冒険者")))
        .andExpect(content().string(containsString("記録から読み取られたこと：出入りの多い短い一日から")))
        .andExpect(content().string(containsString("ヨーロッパ系の名")))
        .andExpect(content().string(containsString("<dt>出身地</dt><dd>グランフォード</dd>")))
        .andExpect(content().string(containsString("<dt>信仰</dt><dd>無信仰</dd>")))
        .andExpect(content().string(containsString("guild-people.svg?v=2#outfit-")))
        .andExpect(content().string(containsString("7 Days to Die・")))
        .andExpect(content().string(containsString("zombieGuild10を1体倒した日から生まれました")))
        .andExpect(content().string(containsString("10:00 古い家でzombieGuild10を倒した")))
        .andExpect(content().string(not(containsString("PRIVATE_WORLD"))))
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
      // A woman knight wears the women's cut of plate; outfits without their own legs get a skirt.
      var look = people.look("女性", i % 2 == 0 ? "騎士" : "鍛冶屋");
      assertThat(look.outfit()).isIn("knight_f", "crafter");
      assertThat(look.layers()).startsWith("body").contains("outfit-" + look.outfit());
      assertThat(look.style()).matches("(--[a-z0-9]+:#[0-9a-f]{6};?)+");
    }
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
