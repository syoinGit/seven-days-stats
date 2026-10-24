package com.yuki.rubinco.portal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import com.yuki.rubinco.portal.repository.M_GamePortalStateRepository;
import com.yuki.rubinco.timeline.repository.T_TimelinePostReactionRepository;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:portal_web_mvp;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.games.memory-budget-mib=16384"
})
@AutoConfigureMockMvc
class PortalWebMvpTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private M_WebAccountRepository accountRepository;
  @Autowired private com.yuki.rubinco.watchpoint.persistence.repository.M_PlayerRepository playerRepository;
  @Autowired private T_TimelinePostRepository timelineRepository;
  @Autowired private T_TimelinePostReactionRepository reactionRepository;
  @Autowired private M_GamePortalStateRepository stateRepository;

  @BeforeEach
  void setUp() {
    reactionRepository.deleteAll();
    timelineRepository.deleteAll();
    stateRepository.findAll().forEach(state -> {
      state.setServerStatus("offline");
      stateRepository.save(state);
    });
    if (accountRepository.findByLoginId("mvp-admin").isEmpty()) {
      M_WebAccount account = new M_WebAccount();
      account.setLoginId("mvp-admin");
      account.setPasswordHash("not-used-by-mock-user");
      account.setRole("ADMIN");
      accountRepository.save(account);
    }
  }

  @Test
  void anonymousPortalShowsPublicHomeAndClosedLoginModal() throws Exception {
    mockMvc.perform(get("/portal").header("Host", "portal.rubinco.link"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("portal-site-rail")))
        .andExpect(content().string(not(containsString("open=\"open\""))))
        .andExpect(content().string(containsString("Watchpoint")))
        .andExpect(content().string(containsString("公開ユーザー登録は受け付けていません")));

    mockMvc.perform(get("/luminite").header("Host", "luminite.rubinco.link"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));

    mockMvc.perform(get("/api/games"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  @Test
  void authenticatedPortalRendersBothGameCardsAndLuminiteShell() throws Exception {
    mockMvc.perform(get("/portal").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string(
            "Content-Security-Policy", containsString("frame-ancestors 'none'")))
        .andExpect(content().string(containsString("Watchpoint")))
        .andExpect(content().string(containsString("Luminite")))
        .andExpect(content().string(not(containsString("portal-game-status"))));

    mockMvc.perform(get("/luminite").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("TERRARIA ONLY")))
        .andExpect(content().string(containsString("接続情報は準備中です")))
        .andExpect(content().string(containsString("Terrariaの記録はまだありません")));
  }

  @Test
  void localLoginStaysInPortalAndReturnsThereAfterAuthentication() throws Exception {
    mockMvc.perform(get("/login?error"))
        .andExpect(redirectedUrl("/portal?login=true&error"));
    mockMvc.perform(get("/portal?login=true&error"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Rubinco")))
        .andExpect(content().string(containsString("name=\"username\"")))
        .andExpect(content().string(containsString("ログインIDまたはパスワードを確認してください")))
        .andExpect(content().string(containsString("open=\"open\"")));
    mockMvc.perform(get("/login").header("Host", "watchpoint.rubinco.link"))
        .andExpect(redirectedUrl("/portal?login=true"));
    var account = accountRepository.findByLoginId("mvp-admin").orElseThrow();
    account.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        .encode("portal-test-password"));
    accountRepository.save(account);
    mockMvc.perform(post("/login").with(csrf())
            .param("username", "mvp-admin").param("password", "portal-test-password"))
        .andExpect(redirectedUrl("/portal"));
    mockMvc.perform(post("/login").with(csrf())
            .param("username", "mvp-admin").param("password", "incorrect"))
        .andExpect(redirectedUrl("/login?error"));
    mockMvc.perform(get("/"))
        .andExpect(redirectedUrl("/portal"));
  }

  @Test
  void sharedTimelineMergesGamesPaginatesAndExcludesHiddenRecords() throws Exception {
    var now = java.time.OffsetDateTime.parse("2026-09-10T12:00:00Z");
    for (int i = 0; i < 22; i++) {
      var event = new com.yuki.rubinco.timeline.entity.T_TimelinePost();
      event.setGameId(i % 2 == 0 ? "7dtd" : "terraria");
      event.setTitle("Shared record " + i);
      event.setMessage("Body " + i + " <script>alert(1)</script>");
      event.setPublishedAt(now.plusMinutes(i));
      event.setPostType("PROGRESS");
      event.setActorType("MEMBER");
      event.setActorName("member");
      event.setSourceType("PORTAL_EVENT");
      event.setSourceHash("shared-test-" + i);
      event.setVisible(i != 21);
      timelineRepository.save(event);
    }
    String html = mockMvc.perform(get("/portal").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("全体タイムライン")))
        .andExpect(content().string(containsString("過去の記録")))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(html).contains("Shared record 20", "Shared record 19", "2026/09/10 21:20")
        .doesNotContain("Shared record 21", "Shared record 0", "<script>alert(1)</script>");
    assertThat(html.indexOf("Shared record 20")).isLessThan(html.indexOf("Shared record 19"));
    mockMvc.perform(get("/portal?page=1").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Shared record 0")))
        .andExpect(content().string(not(containsString("Shared record 20"))));
    mockMvc.perform(get("/portal"))
        .andExpect(content().string(not(containsString("Shared record"))));
  }

  @Test
  void guestLoginOpensTheTimelineAndLoginEntryRemainsOnlyInTheHeader() throws Exception {
    String publicHtml = mockMvc.perform(get("/portal")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(publicHtml).contains("portal-site-rail", "Watchpoint", "Luminite", "全体タイムライン")
        .doesNotContain("open=\"open\"", "class=\"start-button\"");
    assertThat(publicHtml.split("data-open-login", -1)).hasSize(2);
    assertThat(publicHtml.substring(0, publicHtml.indexOf("</header>"))).contains("data-open-login");
    assertThat(publicHtml).contains("<small>7DTD</small>", "<small>Terraria</small>");
    var guest = new M_WebAccount();
    guest.setLoginId("guest");
    guest.setPasswordHash("unused");
    guest.setRole("VIEWER");
    if (accountRepository.findByLoginId("guest").isEmpty()) accountRepository.save(guest);
    var session = new org.springframework.mock.web.MockHttpSession();
    mockMvc.perform(post("/guest-login").session(session).with(csrf()))
        .andExpect(redirectedUrl("/portal#timeline"));
    mockMvc.perform(get("/portal").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("全体タイムライン")))
        .andExpect(content().string(not(containsString("portal-welcome"))))
        .andExpect(content().string(not(containsString("data-open-login"))))
        .andExpect(content().string(not(containsString("class=\"start-button\""))));
    String dashboard = mockMvc.perform(get("/dashboard").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(dashboard.substring(0, dashboard.indexOf("</header>")))
        .contains("portal-header-return", "href=\"/portal\"");
    assertThat(dashboard.substring(dashboard.indexOf("global-rail"), dashboard.indexOf("</nav>", dashboard.indexOf("global-rail"))))
        .doesNotContain("portal-header-return", "portal-return");
  }

  @Test
  void sidebarShowsStoredServerStatesAndGameHeadersShareTheSameActions() throws Exception {
    var state = stateRepository.findById("7dtd").orElseThrow();
    state.setServerStatus("online");
    stateRepository.save(state);
    mockMvc.perform(get("/portal").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("稼働中")))
        .andExpect(content().string(containsString("停止中")))
        .andExpect(content().string(containsString("サイトへ →")))
        .andExpect(content().string(not(containsString("class=\"start-button\""))));
    String[] menus = new String[2];
    String[] paths = {"/dashboard", "/luminite"};
    for (int i = 0; i < paths.length; i++) {
      String html = mockMvc.perform(get(paths[i]).with(user("mvp-admin").roles("ADMIN")))
          .andExpect(status().isOk()).andReturn().getResponse()
          .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      int start = html.indexOf("<nav class=\"auth-nav\"");
      menus[i] = html.substring(start, html.indexOf("</nav>", start))
          .replaceAll("value=\"[^\"]*\"", "value=\"csrf\"");
    }
    assertThat(menus[0]).isEqualTo(menus[1]);
  }

  @Test
  void terrariaTimelinePaginatesWithoutMixingOtherGames() throws Exception {
    for (int i = 0; i < 22; i++) {
      var event = new com.yuki.rubinco.timeline.entity.T_TimelinePost();
      event.setGameId(i == 21 ? "7dtd" : "terraria");
      event.setTitle("Archive record " + i);
      event.setMessage("Archive body " + i);
      event.setPublishedAt(java.time.OffsetDateTime.parse("2026-09-10T12:00:00Z").plusMinutes(i));
      event.setPostType("PROGRESS");
      event.setActorType("MEMBER");
      event.setActorName("member");
      event.setSourceType("PORTAL_EVENT");
      event.setSourceHash("terraria-page-" + i);
      event.setVisible(true);
      timelineRepository.save(event);
    }
    mockMvc.perform(get("/luminite").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("過去の記録")))
        .andExpect(content().string(containsString("Archive record 20")))
        .andExpect(content().string(not(containsString("Archive record 21"))));
    mockMvc.perform(get("/luminite?page=1").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Archive record 0")))
        .andExpect(content().string(not(containsString("Archive record 20"))));
    mockMvc.perform(get("/luminite?page=99").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("このページの記録はありません。")));
  }

  @Test
  void profileUpdatesOnlyTheSignedInUserAndRejectsGuestAndUnsafeIcons() throws Exception {
    mockMvc.perform(get("/portal/profile"))
        .andExpect(redirectedUrl("/login"));
    mockMvc.perform(get("/portal/profile").with(user("guest").roles("VIEWER")))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/portal/profile").with(user("guest").roles("VIEWER")).with(csrf())
            .param("displayName", "Guest change").param("avatarKey", "comet"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/portal/profile").with(user("mvp-admin").roles("ADMIN"))
            .param("displayName", "Explorer").param("avatarKey", "comet"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/portal/profile").with(user("mvp-admin").roles("ADMIN")).with(csrf())
            .param("accountId", "999999").param("displayName", "Explorer").param("avatarKey", "comet"))
        .andExpect(redirectedUrl("/portal/profile"));
    var account = accountRepository.findByLoginId("mvp-admin").orElseThrow();
    assertThat(account.getDisplayName()).isEqualTo("Explorer");
    assertThat(account.getAvatarKey()).isEqualTo("comet");
    mockMvc.perform(get("/portal/profile").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/img/avatars/comet.svg")))
        .andExpect(content().string(containsString("Explorer")));
    mockMvc.perform(post("/portal/profile").with(user("mvp-admin").roles("ADMIN")).with(csrf())
            .param("displayName", "Invalid change").param("avatarKey", "../../invalid"))
        .andExpect(redirectedUrl("/portal/profile"));
    assertThat(accountRepository.findByLoginId("mvp-admin").orElseThrow().getDisplayName())
        .isEqualTo("Explorer");
    mockMvc.perform(get("/portal").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(content().string(containsString("Explorer")))
        .andExpect(content().string(containsString("/img/avatars/comet.svg")));
  }

  @Test
  void portalMaintenanceLetsOnlyAdminsLinkAndUnlinkPlayers() throws Exception {
    String path = "/portal/maintenance/accounts";
    mockMvc.perform(get(path).with(user("member").roles("PLAYER"))).andExpect(status().isForbidden());
    mockMvc.perform(get(path).with(user("guest").roles("VIEWER"))).andExpect(status().isForbidden());
    mockMvc.perform(get("/maintenance/accounts").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(redirectedUrl(path));
    var player = new com.yuki.rubinco.watchpoint.persistence.entity.M_Player();
    player.setPlayerKey("manual-link-player");
    player.setPlayerName("Manual player");
    player.setPlatform("Steam");
    player.setUserId("manual-link-player");
    player = playerRepository.save(player);
    Long accountId = accountRepository.findByLoginId("mvp-admin").orElseThrow().getId();
    mockMvc.perform(post(path + "/player").with(user("member").roles("PLAYER")).with(csrf())
            .param("accountId", accountId.toString()).param("playerId", player.getId().toString()))
        .andExpect(status().isForbidden());
    mockMvc.perform(post(path + "/player").with(user("mvp-admin").roles("ADMIN"))
            .param("accountId", accountId.toString()).param("playerId", player.getId().toString()))
        .andExpect(status().isForbidden());
    mockMvc.perform(post(path + "/player").with(user("mvp-admin").roles("ADMIN")).with(csrf())
            .param("accountId", accountId.toString()).param("playerId", player.getId().toString()))
        .andExpect(redirectedUrl(path));
    assertThat(accountRepository.findById(accountId).orElseThrow().getPlayerId()).isEqualTo(player.getId());
    mockMvc.perform(get(path).with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ユーザーとプレイヤーの連携")))
        .andExpect(content().string(containsString("selected=\"selected\"")));
    mockMvc.perform(post(path + "/player").with(user("mvp-admin").roles("ADMIN")).with(csrf())
            .param("accountId", accountId.toString()).param("playerId", ""))
        .andExpect(redirectedUrl(path));
    assertThat(accountRepository.findById(accountId).orElseThrow().getPlayerId()).isNull();
  }

  @Test
  void serverControlsOfferOnlyManualGamesToMembersAndRequireCsrf() throws Exception {
    mockMvc.perform(get("/portal").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-start-server=\"7dtd\"")))
        .andExpect(content().string(containsString("data-start-server=\"corekeeper\"")))
        .andExpect(content().string(containsString("data-start-server=\"minecraft\"")))
        .andExpect(content().string(not(containsString("data-start-server=\"terraria\""))))
        .andExpect(content().string(containsString("自動起動")));
    mockMvc.perform(get("/portal").with(user("guest").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("data-start-server="))));
    mockMvc.perform(post("/portal/actions/games/corekeeper/start")
            .with(user("guest").roles("VIEWER")).with(csrf()))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/portal/actions/games/minecraft/start")
            .with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/portal/actions/games/terraria/start")
            .with(user("mvp-admin").roles("ADMIN")).with(csrf()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false));
    mockMvc.perform(get("/portal/actions/games/status").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.minecraft.code").value("offline"));
  }

  @Test
  void mockStartRequiresCsrfAndChangesVisibleStateWithoutAws() throws Exception {
    mockMvc.perform(post("/portal/actions/games/7dtd/start")
            .with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/portal/actions/games/7dtd/start")
            .with(user("mvp-admin").roles("ADMIN"))
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.status").value("starting"))
        .andExpect(jsonPath("$.message", containsString("実EC2は操作していません")));

    mockMvc.perform(post("/portal/actions/games/unknown/start")
            .with(user("mvp-admin").roles("ADMIN"))
            .with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  void guestCannotReadUnanonymizedGameApiWhilePlayersCan() throws Exception {
    String event = """
        {"eventType":"PROGRESS","title":"Private record","body":"Player real-name event"}
        """;
    mockMvc.perform(post("/api/games/terraria/events")
            .with(user("mvp-admin").roles("ADMIN"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(event))
        .andExpect(status().isCreated());

    for (String path : new String[] {
        "/api/games", "/api/games/terraria", "/api/games/terraria/timeline",
        "/api/games/terraria/timeline/latest", "/api/games/terraria/progress"}) {
      mockMvc.perform(get(path).with(user("guest").roles("VIEWER")))
          .andExpect(status().isForbidden());
      mockMvc.perform(get(path).with(user("mvp-player").roles("PLAYER")))
          .andExpect(status().isOk());
    }
  }

  @Test
  void timelineWritesRequireAdminAndReadsStayGameScoped() throws Exception {
    String event = """
        {"eventType":"PROGRESS","title":"Eye defeated","body":"Terraria event",
         "progress":55,"stageName":"Hardmode準備","metadata":{"source":"web-test"}}
        """;

    mockMvc.perform(post("/api/games/terraria/events")
            .with(user("mvp-player").roles("PLAYER"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(event))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/games/terraria/events")
            .with(user("mvp-admin").roles("ADMIN"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(event))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.gameId").value("terraria"))
        .andExpect(jsonPath("$.creatorSub").value("local:mvp-admin"));

    mockMvc.perform(get("/api/games/terraria/timeline")
            .with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Terraria event")));

    mockMvc.perform(get("/api/games/7dtd/timeline")
            .with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));

    mockMvc.perform(get("/luminite").with(user("mvp-admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Eye defeated")))
        .andExpect(content().string(containsString("Terraria event")));
  }
}
