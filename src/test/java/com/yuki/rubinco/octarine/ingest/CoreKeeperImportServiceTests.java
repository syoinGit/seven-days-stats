package com.yuki.rubinco.octarine.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.worldprogress.entity.T_ProgressEvent;
import com.yuki.rubinco.worldprogress.entity.T_WorldImportRun;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import com.yuki.rubinco.worldprogress.repository.M_ProgressDefinitionRepository;
import com.yuki.rubinco.worldprogress.repository.T_ProgressEventRepository;
import com.yuki.rubinco.worldprogress.repository.T_ProgressStateRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldSnapshotRepository;
import com.yuki.rubinco.worldprogress.service.ProgressState;
import com.yuki.rubinco.worldprogress.service.WorldImportRunLog;
import com.yuki.rubinco.worldprogress.service.WorldProgressAdminService;
import com.yuki.rubinco.worldprogress.service.WorldSnapshotRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:corekeeper_import;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.corekeeper.quiet-period=0s",
    "app.corekeeper.retry-delay=10ms",
    "app.corekeeper.queue-poll-interval=1h"
})
class CoreKeeperImportServiceTests {
  static final Path DIRECTORY = createDirectory();
  static final Path WORLD_FILE = DIRECTORY.resolve("0.world.gzip");

  @Autowired CoreKeeperImportService importService;
  @Autowired WorldImportRunLog runLog;
  @Autowired WorldSnapshotRecorder recorder;
  @Autowired WorldProgressAdminService admin;
  @Autowired M_GameWorldRepository worlds;
  @Autowired M_ProgressDefinitionRepository definitions;
  @Autowired T_WorldSnapshotRepository snapshots;
  @Autowired T_ProgressStateRepository states;
  @Autowired T_ProgressEventRepository events;
  @Autowired T_TimelinePostRepository timeline;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void worldFile(DynamicPropertyRegistry registry) {
    registry.add("app.corekeeper.world-file", WORLD_FILE::toString);
  }

  @BeforeEach
  void resetWorldProgress() throws Exception {
    jdbc.update("UPDATE M_GAME_WORLD SET latest_snapshot_id = NULL");
    jdbc.update("DELETE FROM T_TIMELINE_POST WHERE source_type = 'WORLD_PROGRESS'");
    for (String table : List.of("T_CK_CONTAINER_ITEM", "T_CK_CONTAINER", "T_CK_CHEST_CHANGE", "T_CK_ITEM_TOTAL",
        "T_CK_ITEM_CHANGE", "T_PROGRESS_EVENT", "T_PROGRESS_STATE", "T_WORLD_IMPORT_RUN",
        "M_GUIDE_RULE", "M_GUIDE_ARTICLE", "T_WORLD_SNAPSHOT")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("DELETE FROM M_GAME_WORLD WHERE generation > 1");
    jdbc.update("UPDATE M_GAME_WORLD SET active = TRUE WHERE game_id = 'corekeeper'");
    Files.copy(CoreKeeperWorldParserTests.FIXTURE, WORLD_FILE, StandardCopyOption.REPLACE_EXISTING);
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().minusSeconds(600)));
  }

  @Test
  void firstImportRecordsAnInitialStateWithoutAnnouncingIt() {
    T_WorldImportRun run = importService.runScheduled().orElseThrow();

    assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
    assertThat(run.getSourceSha256())
        .isEqualTo("b6abea46c5be70c7d6c55aefad8289b9cd2803d46452d3c09a33893f71997ecd");
    assertThat(run.getContainerVersion()).isEqualTo(77);
    var world = worlds.findFirstByGameIdAndActiveTrue("corekeeper").orElseThrow();
    assertThat(world.getLatestSnapshotId()).isEqualTo(run.getWorldSnapshotId());

    Map<String, String> current = currentStates(run.getWorldSnapshotId());
    assertThat(current).containsEntry("great_wall_lowered", "NOT_ACHIEVED")
        .containsEntry("boss_bird", "NOT_ACHIEVED").containsEntry("boss_core", "NOT_ACHIEVED")
        // The game does not derive these from the record list, so absence proves nothing.
        .containsEntry("boss_slime", "UNKNOWN").containsEntry("boss_shaman", "UNKNOWN");
    List<T_ProgressEvent> initial = events.findByWorldSnapshotId(run.getWorldSnapshotId());
    assertThat(initial).hasSize(11).allMatch(event -> "INITIAL".equals(event.getChangeKind()));
    assertThat(timeline.existsBySourceHash("WORLD_PROGRESS:" + initial.getFirst().getId())).isFalse();
  }

  @Test
  void identicalContentIsNotRecordedTwiceEvenWhenTheFileIsTouched() throws Exception {
    importService.runScheduled().orElseThrow();
    assertThat(importService.runScheduled().orElseThrow().getStatus()).isEqualTo("UNCHANGED");
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().minusSeconds(120)));

    T_WorldImportRun touched = importService.runScheduled().orElseThrow();

    assertThat(touched.getStatus()).isEqualTo("UNCHANGED");
    assertThat(snapshots.count()).isOne();
    assertThat(events.count()).isEqualTo(11);
  }

  @Test
  void invalidOrMissingSavesFailWithoutTouchingTheLatestValidSnapshot() throws Exception {
    Long valid = importService.runScheduled().orElseThrow().getWorldSnapshotId();
    byte[] original = Files.readAllBytes(WORLD_FILE);
    Files.write(WORLD_FILE, java.util.Arrays.copyOf(original, original.length / 3));
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().minusSeconds(60)));

    T_WorldImportRun broken = importService.runScheduled().orElseThrow();
    assertThat(broken.getStatus()).isEqualTo("FAILED");
    assertThat(broken.getErrorCode()).isEqualTo("PARSE_FAILED");

    Files.delete(WORLD_FILE);
    assertThat(importService.runScheduled().orElseThrow().getErrorCode()).isEqualTo("SOURCE_MISSING");
    assertThat(worlds.findFirstByGameIdAndActiveTrue("corekeeper").orElseThrow().getLatestSnapshotId())
        .isEqualTo(valid);
    assertThat(currentStates(valid)).containsEntry("great_wall_lowered", "NOT_ACHIEVED");
  }

  @Test
  void fileStillBeingWrittenIsRetriedAndThenSkipped() throws Exception {
    // A modification time in the future never leaves the quiet period, like a save in progress.
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().plusSeconds(3600)));

    T_WorldImportRun run = importService.runScheduled().orElseThrow();
    assertThat(run.getStatus()).isEqualTo("FAILED");
    assertThat(run.getErrorCode()).isEqualTo("SOURCE_CHANGING");
    assertThat(snapshots.count()).isZero();
  }

  @Test
  void aDifferentSeedIsRejectedUntilANewGenerationIsRegistered() throws Exception {
    Long snapshotId = importService.runScheduled().orElseThrow().getWorldSnapshotId();
    jdbc.update("UPDATE T_WORLD_SNAPSHOT SET source_sha256 = 'other', extracted_json = "
        + "'{\"globalSeed\":1}' WHERE world_snapshot_id = ?", snapshotId);
    Files.setLastModifiedTime(WORLD_FILE, FileTime.from(Instant.now().minusSeconds(120)));

    T_WorldImportRun mismatch = importService.runScheduled().orElseThrow();
    assertThat(mismatch.getErrorCode()).isEqualTo("WORLD_MISMATCH");
    assertThat(worlds.findFirstByGameIdAndActiveTrue("corekeeper").orElseThrow().getLatestSnapshotId())
        .isEqualTo(snapshotId);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aNewGenerationStartsItsOwnHistory() {
    var first = importService.runScheduled().orElseThrow();
    var next = admin.startNewGeneration("corekeeper", "二周目", "想定難易度: 中（設定情報）");

    T_WorldImportRun run = importService.runScheduled().orElseThrow();

    assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
    assertThat(run.getGameWorldId()).isEqualTo(next.getId()).isNotEqualTo(first.getGameWorldId());
    assertThat(events.findByWorldSnapshotId(run.getWorldSnapshotId()))
        .allMatch(event -> "INITIAL".equals(event.getChangeKind()));
    assertThat(events.findByGameWorldIdOrderByFirstObservedAtDescIdDesc(next.getId(), Pageable.unpaged()))
        .hasSize(11);
    assertThat(next.getGeneration()).isEqualTo(2);
  }

  @Test
  void laterChangesAreClassifiedAndOnlyRealAchievementsReachTheTimeline() {
    var world = worlds.findFirstByGameIdAndActiveTrue("corekeeper").orElseThrow();
    record(world.getId(), "a", "p1", Map.of("great_wall_lowered", ProgressState.NOT_ACHIEVED));
    var achieved = record(world.getId(), "b", "p1",
        Map.of("great_wall_lowered", ProgressState.ACHIEVED));
    var restored = record(world.getId(), "c", "p1",
        Map.of("great_wall_lowered", ProgressState.NOT_ACHIEVED));
    var reparsed = record(world.getId(), "c", "p2",
        Map.of("great_wall_lowered", ProgressState.ACHIEVED));

    assertThat(kinds(achieved)).containsEntry("great_wall_lowered", "CHANGED");
    assertThat(kinds(restored)).containsEntry("great_wall_lowered", "REGRESSED");
    assertThat(kinds(reparsed)).containsEntry("great_wall_lowered", "PARSER_CHANGE");
    long announced = events.findAll().stream().filter(event ->
        timeline.existsBySourceHash("WORLD_PROGRESS:" + event.getId())).count();
    assertThat(announced).isOne();
    assertThat(recorder.record(world.getId(), input("c", "p2", Map.of()))).isEmpty();
  }

  @Test
  @WithMockUser(roles = {"VIEWER", "ADMIN"})
  void maintenanceServiceRejectsMixedGuestRolesAndMissingAuthentication() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> admin.startNewGeneration("corekeeper", "forged", null))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.worlds("corekeeper"))
        .isInstanceOfAny(org.springframework.security.access.AccessDeniedException.class,
            org.springframework.security.core.AuthenticationException.class);
    assertThat(worlds.findByGameIdOrderByWorldKeyAscGenerationDesc("corekeeper")).hasSize(1);
  }

  @Test
  void manualRequestsDoNotStackAndRunThroughTheQueue() {
    assertThat(runLog.queueManual("corekeeper", "admin")).isPresent();
    assertThat(runLog.queueManual("corekeeper", "admin")).isEmpty();

    T_WorldImportRun run = importService.runNextQueued().orElseThrow();

    assertThat(run.getTriggerType()).isEqualTo("MANUAL");
    assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
    assertThat(importService.runNextQueued()).isEmpty();
    assertThat(runLog.queueManual("corekeeper", "admin")).isPresent();
  }

  private WorldSnapshotRecorder.Recorded record(Long worldId, String sha, String parser,
      Map<String, ProgressState> values) {
    return recorder.record(worldId, input(sha, parser, values)).orElseThrow();
  }

  private WorldSnapshotRecorder.SnapshotInput input(String sha, String parser,
      Map<String, ProgressState> values) {
    Map<String, ProgressState> all = new HashMap<>();
    definitions.findByGameIdOrderBySortOrderAscIdAsc("corekeeper")
        .forEach(definition -> all.put(definition.getProgressKey(), ProgressState.NOT_ACHIEVED));
    all.putAll(values);
    return new WorldSnapshotRecorder.SnapshotInput(sha, 77, parser, OffsetDateTime.now(), null,
        null, "{}", all);
  }

  private Map<String, String> kinds(WorldSnapshotRecorder.Recorded recorded) {
    var keys = keysById();
    return recorded.events().stream().collect(Collectors.toMap(
        event -> keys.get(event.getProgressDefinitionId()), T_ProgressEvent::getChangeKind));
  }

  private Map<String, String> currentStates(Long snapshotId) {
    var keys = keysById();
    return states.findByWorldSnapshotId(snapshotId).stream().collect(Collectors.toMap(
        state -> keys.get(state.getProgressDefinitionId()), state -> state.getState()));
  }

  private Map<Long, String> keysById() {
    return definitions.findByGameIdOrderBySortOrderAscIdAsc("corekeeper").stream().collect(
        Collectors.toMap(definition -> definition.getId(), definition -> definition.getProgressKey()));
  }

  private static Path createDirectory() {
    try {
      return Files.createTempDirectory("corekeeper-import-test");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
