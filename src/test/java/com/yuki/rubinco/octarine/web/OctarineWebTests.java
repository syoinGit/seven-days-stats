package com.yuki.rubinco.octarine.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuki.rubinco.octarine.ingest.CoreKeeperImportService;
import com.yuki.rubinco.octarine.ingest.CoreKeeperWorld;
import com.yuki.rubinco.octarine.ingest.CoreKeeperWorldParser;
import com.yuki.rubinco.octarine.ingest.CoreKeeperWorldStore;
import com.yuki.rubinco.worldprogress.service.WorldSnapshotRecorder;
import java.time.OffsetDateTime;
import java.util.Map;
import com.yuki.rubinco.worldprogress.repository.M_ProgressDefinitionRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldImportRunRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:octarine_web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.corekeeper.quiet-period=0s",
    "app.corekeeper.queue-poll-interval=1h",
    "app.corekeeper.names-dir=src/test/resources/corekeeper-names"
})
@AutoConfigureMockMvc
class OctarineWebTests {
  static final Path WORLD_FILE = createDirectory().resolve("0.world.gzip");

  @Autowired MockMvc mvc;
  @Autowired CoreKeeperImportService importService;
  @Autowired CoreKeeperWorldStore store;
  @Autowired com.yuki.rubinco.octarine.service.OctarineAiFacts aiFacts;
  @Autowired M_ProgressDefinitionRepository definitions;
  @Autowired T_WorldImportRunRepository runs;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void worldFile(DynamicPropertyRegistry registry) {
    registry.add("app.corekeeper.world-file", WORLD_FILE::toString);
  }

  @BeforeEach
  void importSuppliedWorld() throws Exception {
    jdbc.update("UPDATE M_GAME_WORLD SET latest_snapshot_id = NULL, published = TRUE");
    for (String table : List.of("T_CK_CONTAINER_ITEM", "T_CK_CONTAINER", "T_CK_CONTAINER_TRACK", "T_CK_ITEM_TOTAL",
        "T_CK_ITEM_CHANGE", "T_PROGRESS_EVENT", "T_PROGRESS_STATE", "T_WORLD_IMPORT_RUN",
        "M_GUIDE_RULE", "M_GUIDE_ARTICLE", "T_WORLD_SNAPSHOT")) {
      jdbc.update("DELETE FROM " + table);
    }
    Files.copy(Path.of("src/test/resources/corekeeper/new-world.world.gzip"), WORLD_FILE, StandardCopyOption.REPLACE_EXISTING);
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().minusSeconds(600)));
    assertThat(importService.runScheduled().orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
  }

  @Test
  void anonymousVisitorsMustLogIn() throws Exception {
    mvc.perform(get("/octarine")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/octarine/history")).andExpect(status().is3xxRedirection());
  }

  @Test
  void membersSeeOnlyAchievedProgressWithNoHintOfWhatRemains() throws Exception {
    mvc.perform(get("/octarine").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("まだ達成を確認できた項目はありません")))
        .andExpect(content().string(not(containsString("/ 11"))))
        .andExpect(content().string(not(containsString("大壁の解放"))))
        .andExpect(content().string(not(containsString("取得対象外"))))
        .andExpect(content().string(not(containsString("？？？"))))
        .andExpect(content().string(not(containsString("ネタバレ"))))
        .andExpect(content().string(not(containsString("Azeos"))))
        .andExpect(content().string(not(containsString("/octarine/maintenance"))));
    mvc.perform(get("/octarine").param("spoilers", "true").with(member()))
        .andExpect(content().string(not(containsString("Azeos"))));
    mvc.perform(get("/octarine/history").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("大壁の解放"))));

    long wall = definitionId("great_wall_lowered");
    Long snapshot = jdbc.queryForObject("SELECT latest_snapshot_id FROM M_GAME_WORLD WHERE game_id = 'corekeeper'", Long.class);
    jdbc.update("UPDATE T_PROGRESS_STATE SET state = 'ACHIEVED' WHERE world_snapshot_id = ? AND progress_definition_id = ?",
        snapshot, wall);
    mvc.perform(get("/octarine").with(member()))
        .andExpect(content().string(containsString("大壁の解放")))
        .andExpect(content().string(not(containsString("Azeos"))));
  }

  @Test
  void untouchedContainersAndMerchantsStayHiddenUntilTheirContentsChange() throws Exception {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_CONTAINER_TRACK", Integer.class)).isPositive();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_CONTAINER", Integer.class)).isZero();
    mvc.perform(get("/octarine/items").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("テストコイン"))));
    mvc.perform(get("/octarine/items/2021").with(member())).andExpect(status().isNotFound());
    mvc.perform(get("/octarine/chests").param("q", "テストコイン").with(member()))
        .andExpect(content().string(containsString("当てはまる入れ物はありません")));
    mvc.perform(get("/octarine/world").with(member())).andExpect(status().isNotFound());

    storeLater(touchChest(sample(), 233, 83, 12), "e");

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_CONTAINER", Integer.class)).isOne();
    mvc.perform(get("/octarine").with(member()))
        .andExpect(content().string(containsString("ワールドの今")))
        .andExpect(content().string(containsString("使われた入れ物")))
        .andExpect(content().string(not(containsString("生き物"))))
        .andExpect(content().string(not(containsString("作物"))));
    mvc.perform(get("/octarine/items").param("q", "てすとこいん").with(member()))
        .andExpect(content().string(containsString("テストコイン")))
        .andExpect(content().string(containsString("耐久値")))
        .andExpect(content().string(not(containsString("テスト鉄板"))));
    mvc.perform(get("/octarine/items/2021").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("テスト宝箱")))
        .andExpect(content().string(containsString("コアから東233・北83")));
    mvc.perform(get("/octarine/chests").with(member()))
        .andExpect(content().string(containsString("テスト宝箱")))
        .andExpect(content().string(not(containsString("テスト商人"))));
    mvc.perform(get("/octarine/chests").param("q", "テスト商人").with(member()))
        .andExpect(content().string(containsString("当てはまる入れ物はありません")));
    Long id = jdbc.queryForObject("SELECT ck_container_id FROM T_CK_CONTAINER", Long.class);
    mvc.perform(get("/octarine/chests/" + id).with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("テストコイン")));
    mvc.perform(get("/octarine/chests/" + (id + 99999)).with(member())).andExpect(status().isNotFound());

    // Once known, a container stays known even when it goes back to its first contents.
    storeLater(sample(), "d");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_CONTAINER", Integer.class)).isOne();
  }

  @Test
  void aiFactsUseOnlyWhatMembersCanSee() throws Exception {
    assertThat(aiFacts.facts().orElseThrow()).doesNotContain("テストコイン");
    storeLater(touchChest(sample(), 233, 83, 12), "e");
    String facts = aiFacts.facts().orElseThrow();
    assertThat(facts).contains("保管アイテム").contains("テストコイン");
    assertThat(facts).doesNotContain("大壁の解放").doesNotContain("Azeos").doesNotContain("テスト大ボス")
        .doesNotContain("テスト商人").doesNotContain("生き物");
    jdbc.update("UPDATE M_GAME_WORLD SET published = FALSE WHERE game_id = 'corekeeper'");
    assertThat(aiFacts.facts()).isEmpty();
  }

  @Test
  void aPlacedChestCountsOnceItIsFilledAndItsItemsAreLoggedAsChanges() throws Exception {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_ITEM_CHANGE", Integer.class)).isZero();
    var placed = new CoreKeeperWorld.Container("STORAGE", 100, 0, 5, -7, 18, List.of());
    storeLater(withContainer(sample(), placed), "c");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_CONTAINER", Integer.class)).isZero();

    var filled = new CoreKeeperWorld.Container("STORAGE", 100, 0, 5, -7, 18,
        List.of(new CoreKeeperWorld.Slot(0, 2021, 75, 0)));
    storeLater(withContainer(sample(), filled), "b");

    mvc.perform(get("/octarine/items/2021").with(member()))
        .andExpect(content().string(containsString("0 → 75")))
        .andExpect(content().string(containsString("コアから東5・南7")))
        .andExpect(content().string(not(containsString("コアから東233・北83"))));
    mvc.perform(get("/octarine").with(member()))
        .andExpect(content().string(containsString("保管アイテムの変化")))
        .andExpect(content().string(containsString("増えた")));
  }

  private CoreKeeperWorld sample() throws Exception {
    return new CoreKeeperWorldParser(64L << 20, 256L << 20, new ObjectMapper())
        .parse(Files.readAllBytes(Path.of("src/test/resources/corekeeper/new-world.world.gzip")));
  }

  /** The supplied world with one slot of the container at (x, z) set to a new amount. */
  private static CoreKeeperWorld touchChest(CoreKeeperWorld world, int x, int z, int amount) {
    var containers = world.containers().stream().map(container -> {
      if (container.x() != x || container.z() != z) return container;
      var items = new java.util.ArrayList<>(container.items());
      var first = items.get(0);
      items.set(0, new CoreKeeperWorld.Slot(first.slot(), first.objectId(), amount, first.variation()));
      return new CoreKeeperWorld.Container(container.holder(), container.objectId(), container.variation(),
          x, z, container.slotCount(), List.copyOf(items));
    }).toList();
    return copy(world, containers);
  }

  private static CoreKeeperWorld withContainer(CoreKeeperWorld world, CoreKeeperWorld.Container extra) {
    var containers = new java.util.ArrayList<>(world.containers());
    containers.add(extra);
    return copy(world, containers);
  }

  private static CoreKeeperWorld copy(CoreKeeperWorld world, List<CoreKeeperWorld.Container> containers) {
    return new CoreKeeperWorld(world.sourceSha256(), world.containerVersion(), world.decompressedBytes(),
        world.greatWallLowered(), world.recordedObjects(), world.bossKilledFromGameLogic(), world.globalSeed(),
        world.generationJson(), containers, world.skippedEntities());
  }

  private void storeLater(CoreKeeperWorld world, String shaDigit) {
    Long worldId = jdbc.queryForObject("SELECT game_world_id FROM M_GAME_WORLD WHERE game_id = 'corekeeper' AND active",
        Long.class);
    store.store(worldId, false, world, new WorldSnapshotRecorder.SnapshotInput(shaDigit.repeat(64), 77,
        "ck-dotsbin77-3", OffsetDateTime.now(), OffsetDateTime.now(), null,
        "{\"summary\":{\"containers\":1}}", Map.of()));
  }

  @Test
  void onlyAdministratorsReachMaintenanceAndEveryChangeNeedsCsrf() throws Exception {
    mvc.perform(get("/octarine/maintenance").with(member())).andExpect(status().isForbidden());
    mvc.perform(post("/octarine/maintenance/import").with(member()).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/octarine/maintenance/import").with(user("guest").roles("VIEWER", "ADMIN"))
        .with(csrf())).andExpect(status().isForbidden());
    mvc.perform(post("/octarine/maintenance/import").with(admin()))
        .andExpect(status().isForbidden());
    assertThat(runs.findByGameIdAndStatus("corekeeper", "QUEUED")).isEmpty();

    mvc.perform(get("/octarine/maintenance").with(admin()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ck-dotsbin77-3")))
        .andExpect(content().string(containsString("SUCCEEDED")));
    mvc.perform(post("/octarine/maintenance/import").with(admin()).with(csrf()))
        .andExpect(redirectedUrl("/octarine/maintenance"))
        .andExpect(flash().attribute("notice", containsString("登録しました")));
    mvc.perform(post("/octarine/maintenance/import").with(admin()).with(csrf()))
        .andExpect(flash().attribute("error", containsString("登録しませんでした")));
    assertThat(runs.findByGameIdAndStatus("corekeeper", "QUEUED")).hasSize(1);
  }

  @Test
  void guideArticlesFollowRulesNeverShowSpoilersAndAreEscaped() throws Exception {
    long wall = definitionId("great_wall_lowered");
    saveArticle("大壁の先へ", "<script>alert(1)</script>手順メモ", false,
        "condition_" + wall, "SHOW:NOT_ACHIEVED");
    saveArticle("大壁を越えた後", "後半の目標", false, "condition_" + wall, "SHOW:ACHIEVED");
    saveArticle("ネタバレ記事", "未到達ボスの話", true, "condition_" + wall, "");

    mvc.perform(get("/octarine").with(member()))
        .andExpect(content().string(containsString("大壁の先へ")))
        .andExpect(content().string(containsString("&lt;script&gt;")))
        .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
        .andExpect(content().string(not(containsString("大壁を越えた後"))))
        .andExpect(content().string(not(containsString("ネタバレ記事"))))
        .andExpect(content().string(not(containsString("ネタバレを含む記事"))));
    mvc.perform(get("/octarine").param("spoilers", "true").with(member()))
        .andExpect(content().string(not(containsString("ネタバレ記事"))));

    long unsupported = definitionId("boss_slime");
    mvc.perform(post("/octarine/maintenance/articles").with(admin()).with(csrf())
            .param("title", "未検証条件").param("body", "本文").param("published", "true")
            .param("condition_" + unsupported, "SHOW:NOT_ACHIEVED"))
        .andExpect(flash().attribute("error", containsString("表示条件が不正")));
    mvc.perform(post("/octarine/maintenance/articles").with(admin()).with(csrf())
            .param("title", "危険なリンク").param("body", "本文")
            .param("referenceUrl", "javascript:alert(1)"))
        .andExpect(flash().attribute("error", containsString("http(s)")));
  }

  @Test
  void unpublishedWorldIsHiddenFromMembersButNotAdministrators() throws Exception {
    jdbc.update("UPDATE M_GAME_WORLD SET published = FALSE WHERE game_id = 'corekeeper'");
    mvc.perform(get("/octarine").with(member()))
        .andExpect(content().string(containsString("準備中")))
        .andExpect(content().string(not(containsString("まだ達成を確認できた項目はありません"))));
    mvc.perform(get("/octarine").with(admin()))
        .andExpect(content().string(containsString("まだ達成を確認できた項目はありません")));
  }

  @Test
  void octarineHostOpensTheOctarineSite() throws Exception {
    mvc.perform(get("/").with(request -> {
      request.setServerName("octarine.rubinco.link");
      return request;
    })).andExpect(redirectedUrl("/octarine"));
  }

  private void saveArticle(String title, String body, boolean spoiler, String conditionName,
      String condition) throws Exception {
    MockHttpServletRequestBuilder request = post("/octarine/maintenance/articles")
        .with(admin()).with(csrf()).param("title", title).param("body", body)
        .param("published", "true").param("spoiler", String.valueOf(spoiler))
        .param(conditionName, condition);
    mvc.perform(request).andExpect(flash().attribute("notice", containsString("保存しました")));
  }

  private long definitionId(String key) {
    return definitions.findByGameIdOrderBySortOrderAscIdAsc("corekeeper").stream()
        .filter(definition -> definition.getProgressKey().equals(key)).findFirst().orElseThrow()
        .getId();
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor member() {
    return user("member").roles("PLAYER");
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
    return user("admin").roles("ADMIN");
  }

  private static Path createDirectory() {
    try {
      return Files.createTempDirectory("octarine-web-test");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
