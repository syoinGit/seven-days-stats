package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.yuki.rubinco.portal.repository.M_GamePortalStateRepository;
import com.yuki.rubinco.timeline.repository.T_TimelinePostReactionRepository;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.timeline.service.TimelinePostService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:game_portal_mvp;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
@WithMockUser(roles = "ADMIN")
class GamePortalMvpTests {
  @Autowired private GamePortalService portalService;
  @Autowired private GameTimelineService timelineService;
  @Autowired private TimelinePostService watchpointTimelineService;
  @Autowired private GameStartService startService;
  @Autowired private T_TimelinePostRepository timelineRepository;
  @Autowired private T_TimelinePostReactionRepository reactionRepository;
  @Autowired private M_GamePortalStateRepository stateRepository;

  @Autowired private PlatformTransactionManager transactionManager;

  private UsernamePasswordAuthenticationToken admin;

  @BeforeEach
  void setUp() {
    reactionRepository.deleteAll();
    timelineRepository.deleteAll();
    stateRepository.findAll().forEach(state -> {
      state.setServerStatus("offline");
      stateRepository.save(state);
    });
    admin = new UsernamePasswordAuthenticationToken(
        "portal-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  @Test
  void automaticTerrariaCannotBeStartedAndAllManualGamesHaveStateRows() {
    assertThat(startService.requestStart("terraria", "portal-admin").accepted()).isFalse();
    assertThat(portalService.canStart("terraria")).isFalse();
    for (String id : List.of("7dtd", "corekeeper", "minecraft")) {
      assertThat(portalService.canStart(id)).isTrue();
      assertThat(stateRepository.findById(id)).isPresent();
    }
    assertThat(portalService.sites()).extracting(GamePortalService.SiteCard::id)
        .containsExactly("7dtd", "terraria");
  }

  @Test
  void storesBothGamesInOneTimelineAndFiltersByGame() {
    timelineService.create("7dtd", input("Horde incoming", 25, "Day 7"), admin);
    timelineService.create("terraria", input("Eye defeated", 50, "Hardmode準備"), admin);

    assertThat(timelineService.timeline("7dtd", 0, 20))
        .singleElement().extracting(GameTimelineService.TimelineEventView::title)
        .isEqualTo("Horde incoming");
    assertThat(timelineService.timeline("terraria", 0, 20))
        .singleElement().satisfies(event -> {
          assertThat(event.gameId()).isEqualTo("terraria");
          assertThat(event.creatorSub()).isEqualTo("local:portal-admin");
          assertThat(event.metadata()).containsEntry("source", "test");
        });
    assertThat(timelineRepository.count()).isEqualTo(2);
  }

  @Test
  void eventProgressUpdatesPortalSummary() {
    timelineService.create("terraria", input("Boss cleared", 64, "Hardmode"), admin);

    GamePortalService.GameCard terraria = portalService.card("terraria");
    assertThat(terraria.progress()).isEqualTo(64);
    assertThat(terraria.stage()).isEqualTo("Hardmode");
    assertThat(terraria.latestTimeline()).isEqualTo("Event body");
    List<GamePortalService.GameCard> cards = portalService.cards();
    assertThat(cards)
        .extracting(GamePortalService.GameCard::id).containsExactly("7dtd", "terraria", "corekeeper", "minecraft");
    assertThat(cards).contains(terraria);
  }

  @Test
  void backdatedEventsDoNotRegressCurrentProgress() {
    timelineService.create("terraria", new GameTimelineService.EventInput(
        "PROGRESS", "Current", "Current event", OffsetDateTime.parse("2026-09-05T12:00:00Z"),
        70, "Hardmode", Map.of()), admin);
    timelineService.create("terraria", new GameTimelineService.EventInput(
        "PROGRESS", "Imported history", "Older event", OffsetDateTime.parse("2026-08-01T12:00:00Z"),
        20, "Pre-Hardmode", Map.of()), admin);

    GamePortalService.GameCard terraria = portalService.card("terraria");
    assertThat(terraria.progress()).isEqualTo(70);
    assertThat(terraria.stage()).isEqualTo("Hardmode");
  }

  @Test
  void clearingTheLastProgressEventRestoresCatalogDefaults() {
    var created = timelineService.create("terraria", input("Temporary", 90, "Final boss"), admin);

    timelineService.update("terraria", created.eventId(), new GameTimelineService.EventInput(
        "NOTE", "Correction", "No progress information", null, null, "  ", Map.of()), admin);

    var defaults = portalService.configuredGame("terraria");
    var card = portalService.card("terraria");
    assertThat(card.progress()).isEqualTo(defaults.getProgress());
    assertThat(card.stage()).isEqualTo(defaults.getStage());
  }

  @Test
  void clearingLatestProgressFallsBackToEarlierEventAndKeepsItsTimestamp() {
    timelineService.create("terraria", new GameTimelineService.EventInput(
        "PROGRESS", "Earlier", "Earlier progress", OffsetDateTime.parse("2026-08-01T12:00:00Z"),
        20, "Caverns", Map.of()), admin);
    var latest = timelineService.create("terraria", new GameTimelineService.EventInput(
        "PROGRESS", "Latest", "Later progress", OffsetDateTime.parse("2026-09-01T12:00:00Z"),
        70, "Hardmode", Map.of()), admin);

    var updated = timelineService.update("terraria", latest.eventId(),
        new GameTimelineService.EventInput("NOTE", "Correction", "No progress", null,
            null, null, Map.of()), admin);

    assertThat(updated.occurredAt()).isEqualTo(latest.occurredAt());
    assertThat(portalService.card("terraria").progress()).isEqualTo(20);
    assertThat(portalService.card("terraria").stage()).isEqualTo("Caverns");
  }

  @Test
  void eventUpdatesWaitForAnInFlightStartAndPreserveItsStatus() throws Exception {
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    CountDownLatch eventEntered = new CountDownLatch(1);
    GameServerStartGateway gateway = (gameId, actor) -> {
      gatewayEntered.countDown();
      try {
        if (!releaseGateway.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test gateway was not released");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
      return new GameServerStartGateway.StartResult(true, "starting", "accepted");
    };
    var service = new GameStartService(portalService, stateRepository, gateway);
    var transaction = new TransactionTemplate(transactionManager);
    var start = CompletableFuture.supplyAsync(() -> transaction.execute(
        ignored -> service.requestStart("7dtd", "admin")));
    CompletableFuture<GameTimelineService.TimelineEventView> event = null;
    try {
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();
      event = CompletableFuture.supplyAsync(() -> {
        eventEntered.countDown();
        return timelineService.create("7dtd", input("Concurrent progress", 81, "Moon Lord"), admin);
      });
      assertThat(eventEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var pendingEvent = event;
      assertThatThrownBy(() -> pendingEvent.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
    } finally {
      releaseGateway.countDown();
      start.get(5, TimeUnit.SECONDS);
      if (event != null) {
        event.get(5, TimeUnit.SECONDS);
      }
    }

    var state = stateRepository.findById("7dtd").orElseThrow();
    assertThat(state.getServerStatus()).isEqualTo("starting");
    assertThat(state.getProgress()).isEqualTo(81);
    assertThat(state.getStageName()).isEqualTo("Moon Lord");
  }

  @Test
  void timelineHandlesOffsetsBeyondTheJpaIntegerLimit() {
    assertThat(timelineService.timeline("terraria", Integer.MAX_VALUE, 50)).isEmpty();
  }

  @Test
  void terrariaPostTypesCannotAffectWatchpointPublishingState() {
    timelineService.create("terraria", new GameTimelineService.EventInput(
        "SURVIVOR_MARK", "Terraria mark", "Not a Watchpoint NPC post", null,
        null, null, Map.of()), admin);

    assertThat(watchpointTimelineService.latestMarkPostDate()).isEmpty();
  }

  @Test
  void updatingAnEventPreservesItsOriginalCreator() {
    var created = timelineService.create("terraria", input("Original", 30, "Caverns"), admin);
    var editor = new UsernamePasswordAuthenticationToken(
        "second-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    var updated = timelineService.update(
        "terraria", created.eventId(), input("Updated", 35, "Underworld"), editor);

    assertThat(updated.creatorSub()).isEqualTo("local:portal-admin");
    assertThat(updated.title()).isEqualTo("Updated");
  }

  @Test
  void localCreatorIdentifierIsBoundedToTheDatabaseColumn() {
    String longLogin = "member-" + "x".repeat(100);
    var authentication = new UsernamePasswordAuthenticationToken(
        longLogin, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    var created = timelineService.create(
        "terraria", input("Bounded creator", 30, "Caverns"), authentication);

    assertThat(created.creatorSub()).hasSize(80).startsWith("local:member-");
  }

  @Test
  void eventMetadataIsDefensivelyCopied() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source", "client");
    var input = new GameTimelineService.EventInput(
        "PROGRESS", "Immutable metadata", "Event body", null, 30, "Caverns", metadata);
    metadata.put("source", "mutated");

    var created = timelineService.create("terraria", input, admin);

    assertThat(created.metadata()).containsEntry("source", "client");
    assertThatThrownBy(() -> created.metadata().put("source", "mutated"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void mockStartPersistsStartingWithoutCallingAws() {
    var result = startService.requestStart("7dtd", "portal-admin");

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo("starting");
    assertThat(result.message()).contains("実EC2は操作していません");
    assertThat(stateRepository.findById("7dtd").orElseThrow().getServerStatus())
        .isEqualTo("starting");

    var repeated = startService.requestStart("7dtd", "portal-admin");
    assertThat(repeated.accepted()).isFalse();
    assertThat(repeated.status()).isEqualTo("starting");
  }

  @Test
  void startIsRejectedUnlessTheConfiguredServerIsOffline() {
    var state = stateRepository.findById("7dtd").orElseThrow();
    state.setServerStatus("online");
    stateRepository.save(state);

    var online = startService.requestStart("7dtd", "portal-admin");
    assertThat(online.accepted()).isFalse();
    assertThat(online.status()).isEqualTo("online");
    assertThat(online.message()).contains("すでに稼働");

    state.setServerStatus("maintenance");
    stateRepository.save(state);
    var maintenance = startService.requestStart("7dtd", "portal-admin");
    assertThat(maintenance.accepted()).isFalse();
    assertThat(maintenance.status()).isEqualTo("maintenance");

    var unknown = startService.requestStart("unknown", "portal-admin");
    assertThat(unknown.accepted()).isFalse();
    assertThat(unknown.status()).isEqualTo("error");
  }

  private GameTimelineService.EventInput input(String title, int progress, String stage) {
    return new GameTimelineService.EventInput(
        "PROGRESS", title, "Event body", null, progress, stage, Map.of("source", "test"));
  }
}
