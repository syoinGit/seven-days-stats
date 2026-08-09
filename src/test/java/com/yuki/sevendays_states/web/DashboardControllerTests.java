package com.yuki.sevendays_states.web;

import com.yuki.sevendays_states.service.AiCommentService;
import com.yuki.sevendays_states.service.PlayerSocialService;
import com.yuki.sevendays_states.service.TimelinePostService;
import com.yuki.sevendays_states.entity.ReactionType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sevendays_states_web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class DashboardControllerTests {

  @Autowired
  private DashboardController controller;

  @Test
  void dashboardReturnsViewAndModel() {
    ConcurrentModel model = new ConcurrentModel();

    String viewName = controller.index(model, null);

    assertThat(viewName).isEqualTo("dashboard");
    assertThat(model).containsKeys("dashboard", "timelinePage");
  }

  @Test
  void combinesPostsAndGameEventsInOneNewestFirstTimeline() {
    DashboardViewService.TravelEntry event = new DashboardViewService.TravelEntry(
        "2026-08-05 19:40:00", "KILL", "combat", "PlayerA", "討伐", "zombie",
        "PlayerAがゾンビを討伐した", "荒野", "10, 20, 30");
    var post = new PlayerSocialService.PostView(
        1L, 10L, "PlayerB", "探索いってきます", "2026-08-05 19:41:00", 2, true, true);

    List<DashboardController.TimelineItem> timeline = DashboardController.timeline(
        List.of(event), List.of(post));

    assertThat(timeline)
        .extracting(DashboardController.TimelineItem::itemType)
        .containsExactly("POST", "EVENT");
  }

  @Test
  void timelinePageReturnsOnlyOneSmallPageAndCursorForOlderEntries() {
    List<DashboardViewService.TravelEntry> events = IntStream.range(0, 30)
        .mapToObj(index -> travelEntry(
            "2026-08-05 %02d:%02d:00".formatted(18 + index / 12, (index % 12) * 5),
            "Player" + index))
        .toList();

    DashboardController.TimelinePage page = DashboardController.timelinePage(
        events, List.of(), List.of(), 0);

    assertThat(page.items()).hasSize(12);
    assertThat(page.nextOffset()).isEqualTo(12);
    assertThat(page.hasMore()).isTrue();
  }

  @Test
  void mixesBedrockCommentsIntoTimelineByPublishedTime() {
    DashboardViewService.TravelEntry event = travelEntry("2026-08-05 19:40:00", "PlayerA");
    var aiComment = new AiCommentService.AiCommentEntry(
        2L, null, "WATCHPOINT観測記録", "探索範囲が広がっています。",
        OffsetDateTime.of(2026, 8, 5, 10, 42, 0, 0, ZoneOffset.UTC), "AWS_BEDROCK",
        null, List.of());

    List<DashboardController.TimelineItem> timeline = DashboardController.timeline(
        List.of(event), List.of(), List.of(aiComment));

    assertThat(timeline).extracting(DashboardController.TimelineItem::itemType)
        .containsExactly("AI", "EVENT");
    assertThat(timeline.getFirst().actor()).isEqualTo("WATCHPOINT");
    assertThat(timeline.getFirst().tone()).isEqualTo("ai");
  }

  @Test
  void samplesAtMostOneGameEventPerFiveMinuteWindowWithoutDroppingPosts() {
    var firstEvent = travelEntry("2026-08-05 19:40:10", "PlayerA");
    var sameWindowEvent = travelEntry("2026-08-05 19:44:59", "PlayerB");
    var nextWindowEvent = travelEntry("2026-08-05 19:45:00", "PlayerC");
    var post = new PlayerSocialService.PostView(
        1L, 10L, "PlayerD", "投稿", "2026-08-05 19:42:00", 0, false, true);

    List<DashboardController.TimelineItem> timeline = DashboardController.timeline(
        List.of(firstEvent, sameWindowEvent, nextWindowEvent), List.of(post));

    assertThat(timeline).filteredOn(item -> item.itemType().equals("EVENT")).hasSize(2);
    assertThat(timeline).filteredOn(item -> item.itemType().equals("POST")).hasSize(1);
    assertThat(DashboardController.sampledEvents(List.of(firstEvent, sameWindowEvent)))
        .isEqualTo(DashboardController.sampledEvents(List.of(firstEvent, sameWindowEvent)));
  }

  @Test
  void alwaysKeepsLoginLogoutAndHordeEventsOutsideFiveMinuteSampling() {
    var regularA = travelEntry("2026-08-05 19:40:10", "PlayerA");
    var regularB = travelEntry("2026-08-05 19:41:10", "PlayerB");
    var login = event("2026-08-05 19:41:30", "JOIN", "PlayerC");
    var logout = event("2026-08-05 19:42:10", "LEAVE", "PlayerA");
    var horde = event("2026-08-05 19:43:10", "WANDERING_HORDE", "PlayerB");

    List<DashboardViewService.TravelEntry> sampled = DashboardController.sampledEvents(
        List.of(regularA, regularB, login, logout, horde));

    assertThat(sampled).extracting(DashboardViewService.TravelEntry::kind)
        .contains("JOIN", "LEAVE", "WANDERING_HORDE");
    assertThat(sampled).filteredOn(item -> "KILL".equals(item.kind())).hasSize(1);
  }

  @Test
  void movesStalePlayerPostsBelowTheLiveViewportWithoutDeletingThem() {
    List<DashboardViewService.TravelEntry> events = IntStream.range(0, 20)
        .mapToObj(index -> travelEntry(
            "2026-08-05 %02d:%02d:00".formatted(18 + index / 12, (index % 12) * 5),
            "Player" + index))
        .toList();
    var stalePost = new PlayerSocialService.PostView(
        1L, 10L, "PlayerB", "古い投稿", "2026-08-05 16:00:00", 0, false, true);

    List<DashboardController.TimelineItem> timeline = DashboardController.timeline(
        events, List.of(stalePost));

    assertThat(timeline.subList(0, 18))
        .noneMatch(item -> item.itemType().equals("POST"));
    assertThat(timeline).anyMatch(item -> item.itemType().equals("POST"));
  }

  private static DashboardViewService.TravelEntry travelEntry(String occurredAt, String actor) {
    return new DashboardViewService.TravelEntry(
        occurredAt, "KILL", "combat", actor, "討伐", "zombie",
        actor + "がゾンビを討伐した", "荒野", "10, 20, 30");
  }

  private static DashboardViewService.TravelEntry event(
      String occurredAt,
      String kind,
      String actor) {
    return new DashboardViewService.TravelEntry(
        occurredAt, kind, "warning", actor, "発生", "",
        actor + "の近くでイベントが発生した", "荒野", "10, 20, 30");
  }

  @Test
  void oldCommunityRouteRedirectsToUnifiedTimeline() {
    assertThat(controller.community()).isEqualTo("redirect:/dashboard#timeline");
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
    template += Files.readString(Path.of("src/main/resources/templates/diary-maintenance.html"));
    template += Files.readString(Path.of("src/main/resources/templates/diary-generation-data.html"));
    template += Files.readString(Path.of("src/main/resources/templates/diary-editor.html"));
    template += Files.readString(Path.of("src/main/resources/templates/diaries.html"));
    template += Files.readString(Path.of("src/main/resources/templates/diary-detail.html"));

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
    assertThat(Path.of("src/main/resources/static/img/connection-monitor-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/horde-watch-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/air-drop-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/field-journal-avatar.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/survivor-karen-avatar.png")).exists();
  }

  @Test
  void karenPopularityAddsRealReactionsToTheStoredAudience() {
    var post = new TimelinePostService.PostView(
        99L, null, "サバイバーカレン", "今日は遠出。", "2026-08-09 12:00:00",
        "", "SURVIVOR_KAREN", "", "", "https://cdn.example.com/karen.png", 1_200,
        "TRAVEL", java.util.Map.of(ReactionType.NICE, 2L, ReactionType.LAUGH, 1L), null, false);

    DashboardController.TimelineItem item = DashboardController.TimelineItem.post(post);

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
  void everyPageDeclaresTheSiteIcons() throws Exception {
    try (var templates = Files.list(Path.of("src/main/resources/templates"))) {
      assertThat(templates.filter(path -> path.toString().endsWith(".html")))
          .allSatisfy(path -> assertThat(Files.readString(path))
              .contains("/img/site-icon-64.png", "/img/apple-touch-icon.png"));
    }
    assertThat(Path.of("src/main/resources/static/img/site-icon-64.png")).exists();
    assertThat(Path.of("src/main/resources/static/img/apple-touch-icon.png")).exists();
  }

  @Test
  void diaryMaintenanceRoutesReturnTheirViews() {
    LocalDate date = LocalDate.of(2026, 8, 2);
    ConcurrentModel listModel = new ConcurrentModel();
    ConcurrentModel dataModel = new ConcurrentModel();
    ConcurrentModel editorModel = new ConcurrentModel();

    assertThat(controller.diaryMaintenance(listModel)).isEqualTo("diary-maintenance");
    assertThat(controller.diaryGenerationData(date, dataModel)).isEqualTo("diary-generation-data");
    assertThat(controller.diaryEditor(date, editorModel)).isEqualTo("diary-editor");
    assertThat(listModel).containsKey("days");
    assertThat(dataModel).containsKey("packet");
    assertThat(editorModel).containsKeys("packet", "editorEnabled");
  }

  @Test
  void adminNavigationIncludesDiaryMaintenanceAndKarenTestAction() throws Exception {
    String navigation = Files.readString(
        Path.of("src/main/resources/templates/fragments/navigation.html"));
    String bedrockTest = Files.readString(
        Path.of("src/main/resources/templates/ai-analysis-test.html"));

    assertThat(navigation)
        .contains("@{/maintenance/diaries}", "日誌メンテ", "diary-maintenance");
    assertThat(bedrockTest)
        .contains("@{/maintenance/ai-analysis/test/karen}", "Karenの新規投稿");
  }

  @Test
  void publicDiaryListReturnsDatabaseBackedView() {
    ConcurrentModel model = new ConcurrentModel();

    assertThat(controller.diaries(model)).isEqualTo("diaries");
    assertThat(model).containsKey("diaries");
  }
}
