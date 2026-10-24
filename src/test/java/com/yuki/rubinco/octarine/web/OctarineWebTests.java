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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:octarine_web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.corekeeper.quiet-period=0s",
    "app.corekeeper.queue-poll-interval=1h"
})
@AutoConfigureMockMvc
class OctarineWebTests {
  static final Path WORLD_FILE = createDirectory().resolve("0.world.gzip");

  @Autowired MockMvc mvc;
  @Autowired CoreKeeperImportService importService;
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
    for (String table : List.of("T_PROGRESS_EVENT", "T_PROGRESS_STATE", "T_WORLD_IMPORT_RUN",
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
  void membersSeeProgressWithUnreachedBossNamesHidden() throws Exception {
    mvc.perform(get("/octarine").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("0 / 11")))
        .andExpect(content().string(containsString("大壁の解放")))
        .andExpect(content().string(containsString("取得対象外")))
        .andExpect(content().string(containsString("？？？")))
        .andExpect(content().string(containsString("初回観測")))
        .andExpect(content().string(not(containsString("Azeos"))))
        .andExpect(content().string(not(containsString("boss_bird"))))
        .andExpect(content().string(not(containsString("/octarine/maintenance"))));
    mvc.perform(get("/octarine").param("spoilers", "true").with(member()))
        .andExpect(content().string(containsString("Azeos the Sky Titan")));
    mvc.perform(get("/octarine/history").with(member()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("大壁の解放")));
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
        .andExpect(content().string(containsString("ck-dotsbin77-1")))
        .andExpect(content().string(containsString("SUCCEEDED")));
    mvc.perform(post("/octarine/maintenance/import").with(admin()).with(csrf()))
        .andExpect(redirectedUrl("/octarine/maintenance"))
        .andExpect(flash().attribute("notice", containsString("登録しました")));
    mvc.perform(post("/octarine/maintenance/import").with(admin()).with(csrf()))
        .andExpect(flash().attribute("error", containsString("登録しませんでした")));
    assertThat(runs.findByGameIdAndStatus("corekeeper", "QUEUED")).hasSize(1);
  }

  @Test
  void guideArticlesFollowRulesAndSpoilerSettingsAndAreEscaped() throws Exception {
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
        .andExpect(content().string(containsString("ネタバレを含む記事が1件")));
    mvc.perform(get("/octarine").param("spoilers", "true").with(member()))
        .andExpect(content().string(containsString("ネタバレ記事")));

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
        .andExpect(content().string(not(containsString("大壁の解放"))));
    mvc.perform(get("/octarine").with(admin()))
        .andExpect(content().string(containsString("大壁の解放")));
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
