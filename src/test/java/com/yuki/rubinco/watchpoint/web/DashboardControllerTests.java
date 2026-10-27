package com.yuki.rubinco.watchpoint.web;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import static org.assertj.core.api.Assertions.assertThat;
import com.yuki.rubinco.timeline.entity.ReactionType;
import com.yuki.rubinco.timeline.service.TimelinePostService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ui.ConcurrentModel;
import com.yuki.rubinco.portal.service.ServerActivityGate;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rubinco_web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
@AutoConfigureMockMvc
class DashboardControllerTests {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private M_WebAccountRepository accounts;

  @Autowired
  private DashboardController controller;

  @Autowired
  private PlayerInteractionController interactionController;

  @MockitoBean
  private ServerActivityGate serverGate;

  @Test
  void bloodMoonCountdownIsHiddenWhileTheServerIsStopped() throws Exception {
    when(serverGate.stopped("7dtd")).thenReturn(false);
    String running = mockMvc.perform(get("/dashboard").with(user("viewer").roles("PLAYER")))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(running).contains("blood-countdown").doesNotContain("サーバー停止中");

    when(serverGate.stopped("7dtd")).thenReturn(true);
    String stopped = mockMvc.perform(get("/dashboard").with(user("viewer").roles("PLAYER")))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(stopped).contains("サーバー停止中", "0人がオンライン").doesNotContain("blood-countdown");
  }

  @Test
  void dashboardReturnsViewAndModel() {
    ConcurrentModel model = new ConcurrentModel();

    String viewName = controller.index(model, null);

    assertThat(viewName).isEqualTo("dashboard");
    assertThat(model).containsKeys("dashboard", "timelinePage");
  }

  @Test
  void oldCommunityRouteRedirectsToUnifiedTimeline() {
    assertThat(interactionController.community()).isEqualTo("redirect:/dashboard#timeline");
  }

  @Test
  void publicRootShowsLandingPage() {
    assertThat(controller.landing(null)).isEqualTo("landing");
  }

  @Test
  void detailRoutesReturnTheirViewsAndModels() {
    ConcurrentModel serverModel = new ConcurrentModel();
    ConcurrentModel killModel = new ConcurrentModel();
    ConcurrentModel vehicleModel = new ConcurrentModel();
    ConcurrentModel explorationModel = new ConcurrentModel();

    assertThat(controller.server(serverModel)).isEqualTo("server-detail");
    assertThat(controller.kills(killModel)).isEqualTo("kill-detail");
    assertThat(controller.vehicles(vehicleModel)).isEqualTo("vehicle-detail");
    assertThat(controller.exploration(explorationModel)).isEqualTo("exploration-detail");
    assertThat(serverModel).containsKey("server");
    assertThat(killModel).containsKey("kills");
    assertThat(vehicleModel).containsKey("vehicles");
    assertThat(explorationModel).containsKey("exploration");
  }

  @Test
  void dashboardTemplateDoesNotRenderSensitiveIdentifiers() throws Exception {
    String template = Files.readString(Path.of("src/main/resources/templates/dashboard.html"))
        + Files.readString(Path.of("src/main/resources/templates/server-detail.html"))
        + Files.readString(Path.of("src/main/resources/templates/kill-detail.html"))
        + Files.readString(Path.of("src/main/resources/templates/vehicle-detail.html"));
    template += Files.readString(Path.of("src/main/resources/templates/exploration-detail.html"));

    assertThat(template)
        .doesNotContain("Steam_")
        .doesNotContain("EOS_")
        .doesNotContain("platform_id")
        .doesNotContain("cross_platform_id")
        .doesNotContain("native_user_id")
        .doesNotContain("source_log_hash")
        .doesNotContain("source_file")
        .doesNotContain("platformId")
        .doesNotContain("crossPlatformId")
        .doesNotContain("nativeUserId")
        .doesNotContain("sourceLogHash")
        .doesNotContain("sourceFile");
  }

  @Test
  void timelineTemplateUsesPostTonesWithoutTypeBadgesAndCollapsesReactions() throws Exception {
    String template = Files.readString(Path.of("src/main/resources/templates/fragments/timeline.html"));

    assertThat(template)
        .contains("tone-", "reaction-menu", "<details", "class=\"tag\"", "item.tag",
            "item.avatarUrl()", "system-avatar", "feed-media", "karen-popularity");
    assertThat(Path.of("src/main/resources/static/img/watchpoint-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/blood-moon-alert-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/world-intel-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/horde-watch-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/air-drop-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/field-journal-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/survivor-karen-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/survivor-mark-avatar.png")).exists();
  }

  @Test
  void karenPopularityAddsRealReactionsToTheStoredAudience() {
    var post = new TimelinePostService.PostView(
        99L, null, "サバイバーカレン", "今日は遠出。", "2026-08-09 12:00:00",
        "", "SURVIVOR_KAREN", "", "", "https://cdn.example.com/karen.png", 1_200,
        "TRAVEL", java.util.Map.of(ReactionType.NICE, 2L, ReactionType.LAUGH, 1L), null, false);

    TimelinePage.Item item = TimelinePage.Item.from(post);

    assertThat(item.displayLikeCount()).isEqualTo(1_202);
    assertThat(item.imageUrl()).isEqualTo("https://cdn.example.com/karen.png");
    assertThat(item.avatarUrl()).isEqualTo("/img/survivor-karen-avatar.png");
  }

  @Test
  void timelineUsesUnifiedCardsLargerAvatarsAndAViewportBoundSidebar() throws Exception {
    String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
    String dashboard = Files.readString(Path.of("src/main/resources/templates/dashboard.html"));

    assertThat(css)
        .doesNotContain(".feed-item.tone-")
        .contains("width: 64px; height: 64px", "max-height: calc(100vh - 124px)");
    assertThat(dashboard).contains("maxlength=\"100\"", "100文字以内");
  }

  @Test
  void watchpointUsesTheLuminitePageStructure() throws Exception {
    String css = Files.readString(Path.of("src/main/resources/static/css/watchpoint.css"));

    assertThat(css).contains(
        "width:min(1240px, calc(100% - 32px))",
        "grid-template-columns:minmax(0, 1fr) minmax(280px, 340px)",
        ".watchpoint-body .rail { position:static;",
        ".watchpoint-body .status-sidebar { position:sticky; top:84px;");
  }

  @Test
  void everyPageDeclaresTheSiteIcons() throws Exception {
    try (var templates = Files.list(Path.of("src/main/resources/templates"))) {
      // Watchpoint pages use the watchtower; every other page uses the Rubinco ruby.
      assertThat(templates.filter(path -> path.toString().endsWith(".html")))
          .allSatisfy(path -> {
            String html = Files.readString(path);
            if (html.contains("watchpoint-body")) {
              assertThat(html).contains("/img/watchpoint-icon-64.png", "/img/watchpoint-icon.png");
            } else {
              assertThat(html).contains("/img/site-icon-64.png", "/img/apple-touch-icon.png");
            }
          });
    }
    assertThat(Path.of("src/main/resources/static/img/site-icon-64.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/apple-touch-icon.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/watchpoint-icon-64.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/watchpoint-icon.png")).exists();
  }

  @Test
  void navigationHasNoDiaryAndNoRemovedNpcTests() throws Exception {
    String navigation = Files.readString(
        Path.of("src/main/resources/templates/fragments/navigation.html"));
    String bedrockTest = Files.readString(
        Path.of("src/main/resources/templates/ai-analysis-test.html"));

    assertThat(navigation)
        .doesNotContain("/diaries", "冒険日記", "日誌メンテ", "survivor-mark", "Markテスト");
    assertThat(bedrockTest).doesNotContain("karen", "Karen");
  }

  @Test
  void extractedPostRouteRetainsCsrfAndRedirectBehavior() throws Exception {
    var request = post("/posts")
        .param("body", "テスト投稿")
        .with(user("unlinked").roles("PLAYER"));
    mockMvc.perform(request)
        .andExpect(status().isForbidden());
    mockMvc.perform(request.with(csrf()))
        .andExpect(redirectedUrl("/dashboard#timeline"))
        .andExpect(flash().attributeExists("error"));
  }
}
