package com.yuki.rubinco.luminite.web;

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

import com.yuki.rubinco.luminite.ingest.SyntheticTerrariaWorld;
import com.yuki.rubinco.luminite.ingest.SyntheticTerrariaWorld.Slot;
import com.yuki.rubinco.luminite.ingest.TerrariaImportService;
import com.yuki.rubinco.worldprogress.repository.T_WorldImportRunRepository;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:luminite_web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.terraria.quiet-period=0s",
    "app.terraria.queue-poll-interval=1h",
    "app.terraria.names-dir=src/test/resources/terraria-names"
})
@AutoConfigureMockMvc
class LuminiteWebTests {
  static final Path WORLD_FILE = createDirectory().resolve("world.wld");

  @Autowired MockMvc mvc;
  @Autowired TerrariaImportService importService;
  @Autowired T_WorldImportRunRepository runs;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void worldFile(DynamicPropertyRegistry registry) {
    registry.add("app.terraria.world-file", WORLD_FILE::toString);
  }

  @BeforeEach
  void importTwoSaves() throws Exception {
    jdbc.update("UPDATE M_GAME_WORLD SET latest_snapshot_id = NULL, published = TRUE WHERE game_id = 'terraria'");
    for (String table : List.of("T_TERRARIA_CONTAINER_ITEM", "T_TERRARIA_CONTAINER", "T_TERRARIA_ITEM_TOTAL",
        "T_TERRARIA_ITEM_CHANGE", "T_TERRARIA_BESTIARY", "T_TERRARIA_KILL_CHANGE", "T_TERRARIA_SIGN",
        "T_PROGRESS_EVENT", "T_PROGRESS_STATE", "T_WORLD_IMPORT_RUN", "T_WORLD_SNAPSHOT")) {
      jdbc.update("DELETE FROM " + table);
    }
    SyntheticTerrariaWorld world = new SyntheticTerrariaWorld();
    world.chest(4, 12, "<b>倉庫</b>", new Slot(0, 22, 30, 0));
    world.tombstone(20, 9, "古い墓\nOctober 1, 2026");
    world.npcs.add(new SyntheticTerrariaWorld.Npc(22, "アンドリュー", false, 10, 9));
    world.kills.put("BlueSlime", 3);
    save(world);
    assertThat(importService.runScheduled().orElseThrow().getStatus()).isEqualTo("SUCCEEDED");

    world.chests.clear();
    world.chest(4, 12, "<b>倉庫</b>", new Slot(0, 22, 45, 0), new Slot(1, 29, 2, 1));
    world.tombstone(24, 9, "<script>alert(1)</script> was slain.");
    world.kills.put("BlueSlime", 10);
    world.flags.add("DownedBoss1EyeofCthulhu");
    save(world);
    assertThat(importService.runScheduled().orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
  }

  @Test
  void anonymousVisitorsMustLogIn() throws Exception {
    for (String path : List.of("/luminite", "/luminite/items", "/luminite/chests", "/luminite/world")) {
      mvc.perform(get(path)).andExpect(status().is3xxRedirection());
    }
  }

  @Test
  void overviewShowsWorldFactsProgressAndChanges() throws Exception {
    mvc.perform(get("/luminite").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("クトゥルフのめだま")))
        .andExpect(content().string(not(containsString("スライムキング"))))
        .andExpect(content().string(not(containsString("ネタバレ"))))
        .andExpect(content().string(not(containsString(" / 6"))))
        .andExpect(content().string(containsString("テスト鉄塊 +15")))
        .andExpect(content().string(containsString("討伐 +7")))
        .andExpect(content().string(containsString("新しい墓石")))
        .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
        .andExpect(content().string(not(containsString("/luminite/maintenance"))));
  }

  @Test
  void itemPagesExistOnlyForStoredItems() throws Exception {
    mvc.perform(get("/luminite/items").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("テスト鉄塊")))
        .andExpect(content().string(containsString("テスト生命石")));
    mvc.perform(get("/luminite/items").param("q", "せいめい").with(member()))
        .andExpect(content().string(not(containsString("テスト鉄塊"))));
    mvc.perform(get("/luminite/items").param("q", "テスト生命").with(member()))
        .andExpect(content().string(containsString("テスト生命石")))
        .andExpect(content().string(not(containsString("テスト鉄塊"))));
    mvc.perform(get("/luminite/items/22").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("30 → 45")))
        .andExpect(content().string(containsString("&lt;b&gt;倉庫&lt;/b&gt;")))
        .andExpect(content().string(not(containsString("<b>倉庫</b>"))));
    mvc.perform(get("/luminite/items/1").with(member())).andExpect(status().isNotFound());
  }

  @Test
  void chestSearchFindsContainersByItemName() throws Exception {
    mvc.perform(get("/luminite/chests").param("q", "てすと生命").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("テスト宝箱")))
        .andExpect(content().string(containsString("1件")));
    mvc.perform(get("/luminite/chests").param("q", "存在しない").with(member()))
        .andExpect(content().string(containsString("当てはまる入れ物はありません")));
    Long id = jdbc.queryForObject("SELECT terraria_container_id FROM T_TERRARIA_CONTAINER", Long.class);
    mvc.perform(get("/luminite/chests/" + id).with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("テスト大きい テスト生命石")));
    mvc.perform(get("/luminite/chests/" + (id + 999)).with(member())).andExpect(status().isNotFound());
  }

  @Test
  void otherPagesRenderFromTheLatestSnapshot() throws Exception {
    mvc.perform(get("/luminite/bestiary").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("テストスライム")));
    mvc.perform(get("/luminite/world").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("アンドリュー")))
        .andExpect(content().string(containsString("テスト案内人")));
    mvc.perform(get("/luminite/deaths").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("初回取り込み時からあった")))
        .andExpect(content().string(containsString("&lt;script&gt;")))
        .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
    mvc.perform(get("/luminite/history").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("クトゥルフのめだま")))
        .andExpect(content().string(not(containsString("スライムキング"))));
  }

  @Test
  void unpublishedWorldIsHiddenFromMembers() throws Exception {
    jdbc.update("UPDATE M_GAME_WORLD SET published = FALSE WHERE game_id = 'terraria'");
    mvc.perform(get("/luminite").with(member()))
        .andExpect(content().string(containsString("準備中")))
        .andExpect(content().string(not(containsString("テスト世界"))));
    mvc.perform(get("/luminite/items").with(member()))
        .andExpect(content().string(containsString("準備中")))
        .andExpect(content().string(not(containsString("テスト鉄塊"))));
    mvc.perform(get("/luminite/items").with(admin()))
        .andExpect(content().string(containsString("テスト鉄塊")));
  }

  @Test
  void onlyAdministratorsReachMaintenanceAndEveryChangeNeedsCsrf() throws Exception {
    mvc.perform(get("/luminite/maintenance").with(member())).andExpect(status().isForbidden());
    mvc.perform(post("/luminite/maintenance/import").with(member()).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/luminite/maintenance/import").with(admin())).andExpect(status().isForbidden());
    mvc.perform(get("/luminite/maintenance").with(admin()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("tr-wld326-1")))
        .andExpect(content().string(containsString("アイテム名 2件")));
    mvc.perform(post("/luminite/maintenance/import").with(admin()).with(csrf()))
        .andExpect(redirectedUrl("/luminite/maintenance"))
        .andExpect(flash().attribute("notice", containsString("登録しました")));
    assertThat(runs.findByGameIdAndStatus("terraria", "QUEUED")).hasSize(1);
  }

  @Test
  void unchangedSaveDoesNotCreateANewSnapshot() throws Exception {
    Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM T_WORLD_SNAPSHOT", Integer.class);
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().minusSeconds(30)));
    assertThat(importService.runScheduled().orElseThrow().getStatus()).isEqualTo("UNCHANGED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_WORLD_SNAPSHOT", Integer.class)).isEqualTo(before);
  }

  @Test
  void luminiteHostOpensTheLuminiteSite() throws Exception {
    mvc.perform(get("/").with(request -> {
      request.setServerName("luminite.rubinco.link");
      return request;
    })).andExpect(redirectedUrl("/luminite"));
  }

  private static void save(SyntheticTerrariaWorld world) throws Exception {
    Files.write(WORLD_FILE, world.build());
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().minusSeconds(600)));
  }

  private static RequestPostProcessor member() {
    return user("member").roles("PLAYER");
  }

  private static RequestPostProcessor admin() {
    return user("admin").roles("ADMIN");
  }

  private static Path createDirectory() {
    try {
      return Files.createTempDirectory("luminite-web-test");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
