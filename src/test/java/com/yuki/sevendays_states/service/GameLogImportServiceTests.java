package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.repository.M_PlayerRepository;
import com.yuki.sevendays_states.repository.T_EntityKillTransactionRepository;
import com.yuki.sevendays_states.repository.T_LevelXpSummaryTransactionRepository;
import com.yuki.sevendays_states.repository.T_PlayerCurrentStateRepository;
import com.yuki.sevendays_states.repository.T_PlayerJoinTransactionRepository;
import com.yuki.sevendays_states.repository.T_PlayerLeaveTransactionRepository;
import com.yuki.sevendays_states.repository.T_PlayerPositionTransactionRepository;
import com.yuki.sevendays_states.repository.T_ServerMetricRepository;
import com.yuki.sevendays_states.repository.T_SleeperTransactionRepository;
import com.yuki.sevendays_states.repository.T_VehicleCurrentStateRepository;
import com.yuki.sevendays_states.repository.T_VehiclePositionTransactionRepository;
import com.yuki.sevendays_states.repository.T_PlayerStatusRepository;
import com.yuki.sevendays_states.repository.T_WorldEventTransactionRepository;
import com.yuki.sevendays_states.repository.T_TimelinePostReactionRepository;
import com.yuki.sevendays_states.repository.T_TimelinePostRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sevendays_states_log;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.sevendays.log.server-metric-interval-minutes=60"
})
class GameLogImportServiceTests {

  @TempDir
  Path tempDir;

  @Autowired
  private GameLogImportService logImportService;

  @Autowired
  private M_PlayerRepository playerRepository;

  @Autowired
  private T_PlayerJoinTransactionRepository playerJoinRepository;

  @Autowired
  private T_PlayerCurrentStateRepository playerCurrentStateRepository;

  @Autowired
  private T_PlayerLeaveTransactionRepository playerLeaveRepository;

  @Autowired
  private T_PlayerPositionTransactionRepository playerPositionRepository;

  @Autowired
  private T_EntityKillTransactionRepository entityKillRepository;

  @Autowired
  private T_LevelXpSummaryTransactionRepository levelXpSummaryRepository;

  @Autowired
  private T_SleeperTransactionRepository sleeperRepository;

  @Autowired
  private T_ServerMetricRepository serverMetricRepository;

  @Autowired
  private T_WorldEventTransactionRepository worldEventRepository;

  @Autowired
  private T_VehicleCurrentStateRepository vehicleCurrentStateRepository;

  @Autowired
  private T_VehiclePositionTransactionRepository vehiclePositionRepository;

  @Autowired
  private T_PlayerStatusRepository playerStatusRepository;

  @Autowired
  private T_TimelinePostRepository timelinePostRepository;

  @Autowired
  private T_TimelinePostReactionRepository timelineReactionRepository;

  @BeforeEach
  void deleteTransactions() {
    timelineReactionRepository.deleteAll();
    timelinePostRepository.deleteAll();
    playerJoinRepository.deleteAll();
    playerCurrentStateRepository.deleteAll();
    playerLeaveRepository.deleteAll();
    playerPositionRepository.deleteAll();
    entityKillRepository.deleteAll();
    levelXpSummaryRepository.deleteAll();
    sleeperRepository.deleteAll();
    serverMetricRepository.deleteAll();
    vehiclePositionRepository.deleteAll();
    vehicleCurrentStateRepository.deleteAll();
    playerStatusRepository.deleteAll();
    worldEventRepository.deleteAll();
    playerRepository.deleteAll();
  }

  @Test
  void importsTargetLogsAndSkipsMalformedLines() throws Exception {
    Path log = writeLog("""
        malformed log
        2026-07-26T08:18:02 1147.256 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: -162, 52, -857): EntityID=331, PltfmId='Steam_76561198123350583', CrossId='EOS_xxx', OwnerID='Steam_xxx', PlayerName='DDD烈火王テムジン', ClientNumber='3'
        2026-07-26T08:41:32 2557.179 INF Player disconnected: EntityID=331, PltfmId='Steam_76561198123350583', CrossId='EOS_xxx', OwnerID='Steam_xxx', PlayerName='DDD烈火王テムジン', ClientNumber='3'
        2026-07-26T08:22:51 1436.863 INF Entity zombieBusinessMan 347 killed by DDD烈火王テムジン 331
        2026-07-26T08:36:07 2233.109 INF MinEventLogMessage: XP gained during the last level:
        2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromLootThisLevel == 16
        2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromHarvestingThisLevel == 1014
        2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromKillThisLevel == 3950
        2026-07-26T08:24:51 1556.661 INF 1544.871 SleeperVolume -546, 55, -577: Spawning -538, 55, -570 (-34, -36), group 'sleeperHordeStageGS2', class zombieBoe, count 5
        2026-07-26T08:21:24 1349.173 INF 1337.678 SleeperVolume -151, 38, -767: Restoring -144, 39, -765 (-9, -48) 'zombieSteveCrawler', count 0
        2026-07-29T14:07:38 11342.887 INF AIAirDrop: Spawned supply crate at (460.2, 209.1, 32.6), plane is at (461.73, 219.09, 38.19)
        2026-07-29T13:40:42 9726.680 INF 219671 VehicleManager write #0, id 3718, vehicleBicycle, (157.4, 38.0, -733.3), chunk 9, -46
        """);

    GameLogImportResult result = logImportService.importLogFile(log);

    assertThat(result.malformedLines()).isEqualTo(1);
    assertThat(result.playerJoins()).isEqualTo(1);
    assertThat(result.playerLeaves()).isEqualTo(1);
    assertThat(result.entityKills()).isEqualTo(1);
    assertThat(result.levelXpSummaries()).isEqualTo(1);
    assertThat(result.sleeperSpawns()).isEqualTo(1);
    assertThat(result.sleeperRestores()).isEqualTo(1);
    assertThat(result.worldEvents()).isEqualTo(1);
    assertThat(result.vehicleEvents()).isEqualTo(1);
    assertThat(playerJoinRepository.count()).isEqualTo(1);
    assertThat(playerLeaveRepository.count()).isEqualTo(1);
    assertThat(playerPositionRepository.count()).isEqualTo(1);
    assertThat(entityKillRepository.count()).isEqualTo(1);
    assertThat(levelXpSummaryRepository.count()).isEqualTo(1);
    assertThat(sleeperRepository.count()).isEqualTo(2);
    assertThat(worldEventRepository.count()).isEqualTo(1);
    assertThat(vehiclePositionRepository.count()).isEqualTo(1);
    assertThat(timelinePostRepository.findAll())
        .extracting(post -> post.getPostType() + ":" + post.getActorName())
        .contains("LOGIN:CONNECTION MONITOR", "LOGOUT:CONNECTION MONITOR");
  }

  @Test
  void createsOneImmediateLoginPostWithAReadableVariantAndNoDuplicate() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:18:02 1147.256 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: -162, 52, -857): EntityID=331, PltfmId='Steam_76561198123350583', CrossId='EOS_xxx', OwnerID='Steam_xxx', PlayerName='PlayerA', ClientNumber='3'
        """);

    logImportService.importLogFile(log);
    logImportService.importLogFile(log);

    assertThat(timelinePostRepository.findAll()).singleElement().satisfies(post -> {
      assertThat(post.getPostType()).isEqualTo("LOGIN");
      assertThat(post.getMessage()).contains("PlayerAがログインした");
      assertThat(post.getSourceType()).isEqualTo("PLAYER_JOIN");
    });
  }

  @Test
  void recordsRespawnAndTeleportPositionsWithoutCreatingPlaySessions() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:00:00 1000.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 0, 50, 0): EntityID=101, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        2026-07-26T08:20:00 2200.000 INF PlayerSpawnedInWorld (reason: Died, position: 10, 50, 10): EntityID=101, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        2026-07-26T08:30:00 2800.000 INF PlayerSpawnedInWorld (reason: Teleport, position: 20, 50, 20): EntityID=101, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        """);

    GameLogImportResult result = logImportService.importLogFile(log);

    assertThat(result.playerJoins()).isEqualTo(1);
    assertThat(playerJoinRepository.count()).isEqualTo(1);
    assertThat(playerPositionRepository.count()).isEqualTo(3);
  }

  @Test
  void importsWorldEventsAndSkipsDuplicates() throws Exception {
    Path log = writeLog("""
        2026-07-29T14:07:38 11342.887 INF AIAirDrop: Spawned supply crate at (460.2, 209.1, 32.6), plane is at (461.73, 219.09, 38.19)
        2026-07-29T14:09:23 11448.316 INF AIDirector: FindWanderingTargets at player '[type=EntityPlayer, name=hosi42861, id=485]', dist 55.58979
        2026-07-29T14:23:01 12265.980 INF AIDirector: Spawning Scouts2 at (446.0, 39.0, -701.0), to (437.0, 40.0, -621.0)
        2026-07-29T14:23:01 12266.015 INF Spawned [type=EntityZombie, name=zombieScreamer, id=4601] at (447.5, 39.0, -706.5) Day=13 TotalInWave=1 CurrentWave=1
        2026-07-30T10:57:36 36.485 INF BloodMoon SetDay: day 14, last day 7, freq 7, range 0
        """);

    GameLogImportResult first = logImportService.importLogFile(log);
    GameLogImportResult second = logImportService.importLogFile(log);

    assertThat(first.worldEvents()).isEqualTo(5);
    assertThat(second.worldEvents()).isZero();
    assertThat(worldEventRepository.findAll())
        .extracting(row -> row.getEventType() + ":" + row.getPositionX() + ":" + row.getActorPlayerName())
        .containsExactly(
            "AIR_DROP:460:null",
            "WANDERING_HORDE:null:hosi42861",
            "SCOUT_HORDE:446:null",
            "SCREAMER_SPAWN:448:null",
            "BLOOD_MOON:null:null");
  }

  @Test
  void publishesAtMostOneBloodMoonForecastPerDay() throws Exception {
    Path log = writeLog("""
        2026-07-30T10:57:36 36.485 INF BloodMoon SetDay: day 14, last day 7, freq 7, range 0
        2026-07-30T11:57:36 96.485 INF BloodMoon SetDay: day 14, last day 7, freq 7, range 0
        """);

    logImportService.importLogFile(log);

    assertThat(worldEventRepository.findAll()).hasSize(2);
    assertThat(timelinePostRepository.findAll())
        .filteredOn(post -> "BLOOD_MOON".equals(post.getPostType()))
        .singleElement()
        .satisfies(post -> assertThat(post.getMessage()).contains("\n"));
  }

  @Test
  void importsVehicleOwnerAndMovementDistance() throws Exception {
    Path log = writeLog("""
        2026-07-27T11:31:26 3921.931 INF Executing command 'lp' by Telnet from 172.18.0.1:40132
        0. id=171, 魅惑のこし餡ぼでぃ, pos=(581.7, 40.0, -538.9), rot=(-46.4, -73.1, 0.0), remote=True, health=115, deaths=1, zombies=226, players=0, score=196, level=15, pltfmid=Steam_76561198382915826, crossid=EOS_00024b5c4d2546468b7c6775bd927c32, ip=219.107.140.192, ping=5
        Total of 1 in the game
        2026-07-29T13:56:11 10656.556 INF VehicleManager loaded #0, id 2631, [type=EntityBicycle, name=vehicleBicycle, id=2631], (442.2, 38.0, -615.0), chunk 27, -39 (27, -39), owner EOS_00024b5c4d2546468b7c6775bd927c32
        2026-07-29T13:58:16 10781.369 INF 240659 VehicleManager write #1, id 2631, vehicleBicycle, (452.2, 38.0, -615.0), chunk 28, -39
        2026-07-29T14:00:16 10901.575 INF 243053 VehicleManager write #1, id 2631, vehicleBicycle, (452.2, 38.0, -605.0), chunk 28, -38
        """);

    logImportService.importLogFile(log);

    Long ownerPlayerId = playerRepository.findAll().getFirst().getId();
    assertThat(vehiclePositionRepository.findAll())
        .extracting(row -> row.getEventType() + ":" + row.getOwnerPlayerId())
        .containsExactly(
            "VEHICLE_LOADED:" + ownerPlayerId,
            "VEHICLE_WRITE:" + ownerPlayerId,
            "VEHICLE_WRITE:" + ownerPlayerId);
    assertThat(vehiclePositionRepository.findAll())
        .extracting(row -> row.getMovementDistance())
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(BigDecimal.ZERO, new BigDecimal("10.0"), new BigDecimal("10.0"));
    assertThat(vehicleCurrentStateRepository.findById(2631)).hasValueSatisfying(row -> {
      assertThat(row.getOwnerPlayerId()).isEqualTo(ownerPlayerId);
      assertThat(row.getPositionX()).isEqualTo(452);
      assertThat(row.getPositionZ()).isEqualTo(-605);
      assertThat(row.getTotalDistance()).isEqualByComparingTo("20.0");
      assertThat(row.isActive()).isTrue();
    });
  }

  @Test
  void infersVehicleOwnerFromFreshMatchingPlayerPosition() throws Exception {
    Path log = writeLog("""
        2026-07-29T13:58:10 10775.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(452.0, 38.0, -615.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        Total of 1 in the game
        2026-07-29T13:58:16 10781.369 INF Vehicle PostInit [type=EntityBicycle, name=vehicleBicycle, id=2631], (454.0, 38.0, -615.0) (chunk 28, -39), rbPos (0.00, 0.00, 0.00)
        """);

    logImportService.importLogFile(log);

    Long playerId = playerRepository.findAll().getFirst().getId();
    assertThat(vehicleCurrentStateRepository.findById(2631)).hasValueSatisfying(row -> {
      assertThat(row.getOwnerPlayerId()).isEqualTo(playerId);
      assertThat(row.getOwnerCrossPlatformId()).isEqualTo("EOS_a");
      assertThat(row.getOwnerInferenceMethod()).isEqualTo("nearest_fresh_player_position");
    });
    assertThat(vehiclePositionRepository.findAll().getFirst().getOwnerPlayerId()).isEqualTo(playerId);
  }

  @Test
  void doesNotInferVehicleOwnerWhenPlayersAreEquallyClose() throws Exception {
    Path log = writeLog("""
        2026-07-29T13:58:10 10775.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(0.0, 38.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        1. id=172, PlayerB, pos=(4.0, 38.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_b, crossid=EOS_b, ip=127.0.0.2, ping=5
        Total of 2 in the game
        2026-07-29T13:58:16 10781.369 INF Vehicle PostInit [type=EntityBicycle, name=vehicleBicycle, id=2631], (2.0, 38.0, 0.0) (chunk 0, 0), rbPos (0.00, 0.00, 0.00)
        """);

    logImportService.importLogFile(log);

    assertThat(vehicleCurrentStateRepository.findById(2631)).hasValueSatisfying(row -> {
      assertThat(row.getOwnerPlayerId()).isNull();
      assertThat(row.getOwnerInferenceMethod()).isNull();
    });
  }

  @Test
  void recordsPlayerTravelDistanceFromConsecutiveLpPositions() throws Exception {
    Path log = writeLog("""
        2026-07-29T13:58:10 10775.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(0.0, 38.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        Total of 1 in the game
        2026-07-29T13:58:20 10785.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(3.0, 38.0, 4.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        Total of 1 in the game
        """);

    logImportService.importLogFile(log);

    assertThat(playerPositionRepository.findAll())
        .extracting(row -> row.getMovementDistance())
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(BigDecimal.ZERO, new BigDecimal("5.0"));
    assertThat(playerPositionRepository.findAll())
        .extracting(row -> row.getMovementMode())
        .containsExactly("UNKNOWN", "ON_FOOT");
  }

  @Test
  void resetsDistanceWhenVehicleEntityIdIsReusedFarAway() throws Exception {
    Path log = writeLog("""
        2026-07-29T13:50:00 10000.000 INF 100000 VehicleManager write #0, id 2631, vehicleBicycle, (0.0, 38.0, 0.0), chunk 0, 0
        2026-07-29T13:51:00 10060.000 INF 100001 VehicleManager write #0, id 2631, vehicleBicycle, (10.0, 38.0, 0.0), chunk 0, 0
        2026-07-29T13:51:10 10070.000 INF VehicleManager RemoveTrackedVehicle [type=EntityBicycle, name=vehicleBicycle, id=2631], Killed
        2026-07-29T13:52:00 10120.000 INF Vehicle PostInit [type=EntityBicycle, name=vehicleBicycle, id=2631], (1000.0, 38.0, 0.0) (chunk 62, 0), rbPos (0.00, 0.00, 0.00)
        2026-07-29T13:53:00 10180.000 INF 100002 VehicleManager write #0, id 2631, vehicleBicycle, (1010.0, 38.0, 0.0), chunk 63, 0
        """);

    logImportService.importLogFile(log);

    assertThat(vehicleCurrentStateRepository.findById(2631)).hasValueSatisfying(row ->
        assertThat(row.getTotalDistance()).isEqualByComparingTo("10.0"));
    assertThat(vehiclePositionRepository.findAll())
        .extracting(row -> row.getMovementDistance())
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(
            BigDecimal.ZERO,
            new BigDecimal("10.0"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("10.0"));
  }

  @Test
  void attributesVehicleDistanceOnlyToFreshOnlinePlayerNearTheVehicle() throws Exception {
    Path log = writeLog("""
        2026-07-29T13:58:10 10775.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(0.0, 38.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        Total of 1 in the game
        2026-07-29T13:58:11 10776.000 INF 100000 VehicleManager write #0, id 2631, vehicleBicycle, (0.0, 38.0, 0.0), chunk 0, 0
        2026-07-29T13:58:15 10780.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(10.0, 38.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        Total of 1 in the game
        2026-07-29T13:58:16 10781.000 INF 100001 VehicleManager write #0, id 2631, vehicleBicycle, (10.0, 38.0, 0.0), chunk 0, 0
        """);

    logImportService.importLogFile(log);

    Long playerId = playerRepository.findAll().getFirst().getId();
    assertThat(vehiclePositionRepository.findAll().getLast()).satisfies(row -> {
      assertThat(row.isMovementValid()).isTrue();
      assertThat(row.getAttributedPlayerId()).isEqualTo(playerId);
      assertThat(row.getAttributionMethod()).isEqualTo("online_near_vehicle_position");
      assertThat(row.getMovementDistance()).isEqualByComparingTo("10.0");
    });
  }

  @Test
  void doesNotAttributeVehicleMovementToLoggedOutOwner() throws Exception {
    Path log = writeLog("""
        2026-07-29T13:58:10 10775.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(0.0, 38.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        Total of 1 in the game
        2026-07-29T13:58:11 10776.000 INF VehicleManager loaded #0, id 2631, [type=EntityBicycle, name=vehicleBicycle, id=2631], (0.0, 38.0, 0.0), chunk 0, 0 (0, 0), owner EOS_a
        2026-07-29T13:58:20 10785.000 INF Executing command 'lp' by Telnet from app
        Total of 0 in the game
        2026-07-29T13:58:21 10786.000 INF 100001 VehicleManager write #0, id 2631, vehicleBicycle, (10.0, 38.0, 0.0), chunk 0, 0
        """);

    logImportService.importLogFile(log);

    assertThat(vehiclePositionRepository.findAll().getLast()).satisfies(row -> {
      assertThat(row.getOwnerPlayerId()).isNotNull();
      assertThat(row.isMovementValid()).isTrue();
      assertThat(row.getAttributedPlayerId()).isNull();
    });
  }

  @Test
  void rejectsOutOfOrderVehicleMovementAndKeepsNewestCurrentPosition() throws Exception {
    Path newer = writeLog("""
        2026-07-29T14:00:00 10900.000 INF 100002 VehicleManager write #0, id 2631, vehicleBicycle, (100.0, 38.0, 0.0), chunk 6, 0
        """);
    logImportService.importLogFile(newer);
    Path older = writeLog("""
        2026-07-29T13:59:00 10840.000 INF 100001 VehicleManager write #0, id 2631, vehicleBicycle, (0.0, 38.0, 0.0), chunk 0, 0
        """);

    logImportService.importLogFile(older);

    assertThat(vehiclePositionRepository.findAll().getLast().isMovementValid()).isFalse();
    assertThat(vehicleCurrentStateRepository.findById(2631)).hasValueSatisfying(row -> {
      assertThat(row.getPositionX()).isEqualTo(100);
      assertThat(row.getLastUpdated()).isEqualTo(java.time.OffsetDateTime.parse("2026-07-29T14:00:00Z"));
    });
  }

  @Test
  void skipsServerMetricWithinIntervalAndStoresAfterInterval() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:00:00 1000.000 INF Time: 10.00m FPS: 20.00 Heap: 1000.0MB Max: 1100.0MB Chunks: 10 CGO: 1 Ply: 1 Zom: 0 Ent: 1 (2) Items: 0 CO: 1 RSS: 2000.0MB
        2026-07-26T08:30:00 2800.000 INF Time: 40.00m FPS: 20.00 Heap: 1001.0MB Max: 1100.0MB Chunks: 11 CGO: 1 Ply: 1 Zom: 0 Ent: 1 (2) Items: 0 CO: 1 RSS: 2001.0MB
        2026-07-26T09:00:00 4600.000 INF Time: 70.00m FPS: 20.00 Heap: 1002.0MB Max: 1100.0MB Chunks: 12 CGO: 1 Ply: 1 Zom: 0 Ent: 1 (2) Items: 0 CO: 1 RSS: 2002.0MB
        """);

    GameLogImportResult result = logImportService.importLogFile(log);

    assertThat(result.serverMetrics()).isEqualTo(2);
    assertThat(result.skippedServerMetrics()).isEqualTo(1);
    assertThat(serverMetricRepository.count()).isEqualTo(2);
  }

  @Test
  void chatStatusCommandUpdatesPlayerStatus() throws Exception {
    Path log = writeLog("""
        2026-07-29T13:58:10 10775.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 0, 38, 0): EntityID=171, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        2026-07-29T13:58:11 10776.000 INF Executing command 'lp' by Telnet from app
        0. id=171, PlayerA, pos=(0.0, 38.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_a, crossid=EOS_a, ip=127.0.0.1, ping=5
        Total of 1 in the game
        2026-07-29T13:58:20 10785.000 INF Chat (Global): PlayerA: !飯
        """);

    logImportService.importLogFile(log);

    Long playerId = playerRepository.findAll().getFirst().getId();
    assertThat(playerStatusRepository.findById(playerId)).hasValueSatisfying(status -> {
      assertThat(status.getStatus()).isEqualTo("EATING");
      assertThat(status.getSource()).isEqualTo("CHAT");
    });
  }

  @Test
  void doesNotInferPlayerForLevelXpSummary() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:18:02 1147.256 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: -162, 52, -857): EntityID=331, PltfmId='Steam_76561198123350583', CrossId='EOS_xxx', OwnerID='Steam_xxx', PlayerName='DDD烈火王テムジン', ClientNumber='3'
        2026-07-26T08:36:07 2233.109 INF MinEventLogMessage: XP gained during the last level:
        2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromLootThisLevel == 16
        2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromHarvestingThisLevel == 1014
        2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromKillThisLevel == 3950
        """);

    logImportService.importLogFile(log);

    assertThat(levelXpSummaryRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.getPlayerEntityId() + ":" + row.getPlayerInferenceMethod() + ":" + row.getXpTotal())
        .containsExactly("null:null:null:4980");
  }

  @Test
  void importingSameLogTwiceDoesNotCreateDuplicates() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:18:02 1147.256 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: -162, 52, -857): EntityID=331, PltfmId='Steam_76561198123350583', CrossId='EOS_xxx', OwnerID='Steam_xxx', PlayerName='DDD烈火王テムジン', ClientNumber='3'
        2026-07-26T08:22:51 1436.863 INF Entity zombieBusinessMan 347 killed by DDD烈火王テムジン 331
        """);

    GameLogImportResult first = logImportService.importLogFile(log);
    GameLogImportResult second = logImportService.importLogFile(log);

    assertThat(first.playerJoins()).isEqualTo(1);
    assertThat(first.entityKills()).isEqualTo(1);
    assertThat(second.playerJoins()).isZero();
    assertThat(second.entityKills()).isZero();
    assertThat(playerJoinRepository.count()).isEqualTo(1);
    assertThat(playerPositionRepository.count()).isEqualTo(1);
    assertThat(entityKillRepository.count()).isEqualTo(1);
  }

  @Test
  void assignsSleeperToNearestActivePlayerWhenMultiplePlayersAreOnline() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:00:00 1000.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 0, 50, 0): EntityID=101, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        2026-07-26T08:00:10 1010.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 1000, 50, 1000): EntityID=202, PltfmId='Steam_b', CrossId='EOS_b', OwnerID='Steam_b', PlayerName='PlayerB', ClientNumber='2'
        2026-07-26T08:01:00 1060.000 INF 1060.000 SleeperVolume 110, 50, 105: Spawning 120, 50, 100 (7, 6), group 'sleeperHordeStageGS2', class zombieBoe, count 1
        2026-07-26T08:02:00 1120.000 INF 1120.000 SleeperVolume 900, 50, 890: Spawning 910, 50, 900 (56, 56), group 'sleeperHordeStageGS2', class zombieNurse, count 1
        2026-07-26T08:03:00 1180.000 INF 1180.000 SleeperVolume 180, 50, 170: Spawning 190, 50, 180 (11, 11), group 'sleeperHordeStageGS2', class zombieSteve, count 1
        """);

    logImportService.importLogFile(log);

    assertThat(sleeperRepository.findAll())
        .extracting(row -> row.getEntityClass() + ":" + row.getPlayerName() + ":" + row.getPlayerInferenceMethod())
        .containsExactly(
            "zombieBoe:PlayerA:nearest_active_player_latest_position",
            "zombieNurse:PlayerB:nearest_active_player_latest_position",
            "zombieSteve:null:null");
    assertThat(playerPositionRepository.findAll())
        .extracting(row -> row.getPositionSourceType() + ":" + row.getPlayerName() + ":" + row.getPositionX() + ":" + row.getPositionZ())
        .containsExactly(
            "PLAYER_JOIN:PlayerA:0:0",
            "PLAYER_JOIN:PlayerB:1000:1000");
  }

  @Test
  void assignsSleeperToDeterministicNearestPlayerWhenPlayersAreEquidistant() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:00:00 1000.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 0, 50, 0): EntityID=101, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        2026-07-26T08:00:10 1010.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 20, 50, 0): EntityID=202, PltfmId='Steam_b', CrossId='EOS_b', OwnerID='Steam_b', PlayerName='PlayerB', ClientNumber='2'
        2026-07-26T08:01:00 1060.000 INF 1060.000 SleeperVolume 10, 50, 0: Spawning 10, 50, 0 (0, 0), group 'sleeperHordeStageGS2', class zombieBoe, count 1
        """);

    logImportService.importLogFile(log);

    assertThat(sleeperRepository.findAll())
        .extracting(row -> row.getEntityClass() + ":" + row.getPlayerName())
        .containsExactly("zombieBoe:PlayerA");
    assertThat(playerPositionRepository.findAll())
        .extracting(row -> row.getPositionSourceType() + ":" + row.getPlayerName())
        .containsExactly(
            "PLAYER_JOIN:PlayerA",
            "PLAYER_JOIN:PlayerB");
  }

  @Test
  void recordsSleeperPositionWhenOnlyOnePlayerIsOnline() throws Exception {
    Path log = writeLog("""
        2026-07-26T08:00:00 1000.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 0, 50, 0): EntityID=101, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        2026-07-26T08:01:00 1060.000 INF 1060.000 SleeperVolume 110, 50, 105: Spawning 120, 50, 100 (7, 6), group 'sleeperHordeStageGS2', class zombieBoe, count 1
        """);

    logImportService.importLogFile(log);

    assertThat(sleeperRepository.findAll())
        .extracting(row -> row.getEntityClass() + ":" + row.getPlayerName() + ":" + row.getPlayerInferenceMethod())
        .containsExactly("zombieBoe:PlayerA:single_active_player_session");
    assertThat(playerPositionRepository.findAll())
        .extracting(row -> row.getPositionSourceType() + ":" + row.getPlayerName() + ":" + row.getPositionX() + ":" + row.getPositionZ())
        .containsExactly(
            "PLAYER_JOIN:PlayerA:0:0",
            "SLEEPER_INFERRED:PlayerA:120:100");
  }

  @Test
  void usesPlayerListCommandPositionAsDirectCurrentPosition() throws Exception {
    Path log = writeLog("""
        2026-07-26T10:50:00 10200.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 0, 50, 0): EntityID=101, PltfmId='Steam_a', CrossId='EOS_a', OwnerID='Steam_a', PlayerName='PlayerA', ClientNumber='1'
        2026-07-26T10:50:01 10201.000 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: 0, 50, 0): EntityID=202, PltfmId='Steam_b', CrossId='EOS_b', OwnerID='Steam_b', PlayerName='PlayerB', ClientNumber='2'
        2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=101, PlayerA, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=101, deaths=0, zombies=8, players=0, score=8, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=7
        1. id=202, PlayerB, pos=(0.0, 50.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_b, crossid=EOS_b, ip=10.0.0.2, ping=7
        Total of 2 in the game
        2026-07-26T10:54:00 10500.000 INF 10500.000 SleeperVolume -540, 48, -450: Spawning -530, 48, -440 (-33, -27), group 'sleeperHordeStageGS2', class zombieBoe, count 1
        """);

    logImportService.importLogFile(log);

    assertThat(playerPositionRepository.findAll())
        .extracting(row -> row.getPositionSourceType() + ":" + row.getPlayerName() + ":" + row.getPositionX() + ":" + row.getPositionZ())
        .containsExactly(
            "PLAYER_JOIN:PlayerA:0:0",
            "PLAYER_JOIN:PlayerB:0:0",
            "LP_COMMAND:PlayerA:-532:-446",
            "LP_COMMAND:PlayerB:0:0");
    assertThat(sleeperRepository.findAll())
        .extracting(row -> row.getEntityClass() + ":" + row.getPlayerName() + ":" + row.getPlayerInferenceMethod())
        .containsExactly("zombieBoe:PlayerA:nearest_current_state_position");
    assertThat(sleeperRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.getPlayerPositionX() + ":" + row.getPlayerPositionZ())
        .containsExactly("PlayerA:-532:-446");
    assertThat(playerCurrentStateRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.getPositionX() + ":" + row.getPositionZ() + ":" + row.getHealth() + ":" + row.getLevel())
        .containsExactlyInAnyOrder(
            "PlayerA:-532:-446:101:2",
            "PlayerB:0:0:100:1");
  }

  @Test
  void importsCurrentTwoPlayerListIntoCurrentState() throws Exception {
    Path log = writeLog("""
        2026-07-30T12:00:31 3811.560 INF Executing command 'lp' by Telnet from 172.18.0.1:47050
        0. id=171, 魅惑のこし餡ぼでぃ, pos=(450.6, 38.1, -675.8), rot=(-36.6, 877.5, 0.0), remote=True, health=137, deaths=1, zombies=815, players=0, score=742, level=33, pltfmid=Steam_76561198382915826, crossid=EOS_00024b5c4d2546468b7c6775bd927c32, ip=219.107.140.192, ping=5
        1. id=485, hosi42861, pos=(-31.4, 38.1, -705.2), rot=(-28.5, 501.5, 0.0), remote=True, health=125, deaths=1, zombies=162, players=0, score=147, level=25, pltfmid=Steam_76561199276022302, crossid=EOS_0002d3425415470e9632296116cbcc0d, ip=122.131.33.98, ping=5
        Total of 2 in the game
        """);

    GameLogImportResult result = logImportService.importLogFile(log);

    assertThat(result.playerListPositions()).isEqualTo(2);
    assertThat(playerCurrentStateRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.getPositionX() + ":" + row.getPositionZ()
            + ":" + row.getHealth() + ":" + row.getLevel() + ":" + row.isOnline())
        .containsExactlyInAnyOrder(
            "魅惑のこし餡ぼでぃ:451:-676:137:33:true",
            "hosi42861:-31:-705:125:25:true");
  }

  @Test
  void assignsSleeperToNearestPlayerFromCurrentStateWhenLogContextHasNoPlayers() throws Exception {
    Path lpLog = writeLog("""
        2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=101, PlayerA, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=101, deaths=0, zombies=8, players=0, score=8, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=7
        1. id=202, PlayerB, pos=(0.0, 50.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_b, crossid=EOS_b, ip=10.0.0.2, ping=7
        Total of 2 in the game
        """);
    Path sleeperLog = tempDir.resolve("sleeper-log");
    Files.writeString(sleeperLog, """
        2026-07-26T10:54:00 10500.000 INF 10500.000 SleeperVolume -540, 48, -450: Spawning -530, 48, -440 (-33, -27), group 'sleeperHordeStageGS2', class zombieBoe, count 1
        """);

    logImportService.importLogFile(lpLog);
    logImportService.importLogFile(sleeperLog);

    assertThat(sleeperRepository.findAll())
        .extracting(row -> row.getEntityClass() + ":" + row.getPlayerName() + ":" + row.getPlayerInferenceMethod()
            + ":" + row.getPlayerPositionX() + ":" + row.getPlayerPositionZ())
        .containsExactly("zombieBoe:PlayerA:nearest_current_state_position:-532:-446");
  }

  @Test
  void copiesCurrentStatePositionWhenEntityKillIsStored() throws Exception {
    Path log = writeLog("""
        2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=331, DDD烈火王テムジン, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=101, deaths=0, zombies=8, players=0, score=8, level=2, pltfmid=Steam_76561198123350583, crossid=EOS_xxx, ip=10.0.0.1, ping=7
        Total of 1 in the game
        2026-07-26T10:54:00 10500.000 INF Entity zombieBusinessMan 347 killed by DDD烈火王テムジン 331
        """);

    logImportService.importLogFile(log);

    assertThat(entityKillRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.getPlayerPositionX() + ":" + row.getPlayerPositionY() + ":" + row.getPlayerPositionZ())
        .containsExactly("DDD烈火王テムジン:-532:48:-446");
  }

  @Test
  void marksMissingPlayersOfflineOnlyAfterSuccessfulPlayerListResponse() throws Exception {
    Path firstLpLog = tempDir.resolve("first-lp-log");
    Files.writeString(firstLpLog, """
        2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=101, PlayerA, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=101, deaths=0, zombies=8, players=0, score=8, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=7
        1. id=202, PlayerB, pos=(0.0, 50.0, 0.0), rot=(0.0, 0.0, 0.0), remote=True, health=100, deaths=0, zombies=0, players=0, score=0, level=1, pltfmid=Steam_b, crossid=EOS_b, ip=10.0.0.2, ping=7
        Total of 2 in the game
        """);
    Path secondLpLog = tempDir.resolve("second-lp-log");
    Files.writeString(secondLpLog, """
        2026-07-26T10:54:10 10515.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=101, PlayerA, pos=(-530.0, 48.0, -440.1), rot=(-4.2, 369.8, 0.0), remote=True, health=99, deaths=0, zombies=9, players=0, score=9, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=9
        Total of 1 in the game
        """);

    logImportService.importLogFile(firstLpLog);
    logImportService.importLogFile(secondLpLog);

    assertThat(playerCurrentStateRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.isOnline() + ":" + row.getPositionX() + ":" + row.getPing())
        .containsExactlyInAnyOrder(
            "PlayerA:true:-530:9",
            "PlayerB:false:0:7");
  }

  @Test
  void marksAllPlayersOfflineAfterSuccessfulEmptyPlayerListResponse() throws Exception {
    Path firstLpLog = tempDir.resolve("first-lp-log");
    Files.writeString(firstLpLog, """
        2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=101, PlayerA, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=101, deaths=0, zombies=8, players=0, score=8, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=7
        Total of 1 in the game
        """);
    Path emptyLpLog = tempDir.resolve("empty-lp-log");
    Files.writeString(emptyLpLog, """
        2026-07-26T10:54:10 10515.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        Total of 0 in the game
        """);

    logImportService.importLogFile(firstLpLog);
    logImportService.importLogFile(emptyLpLog);

    assertThat(playerCurrentStateRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.isOnline() + ":" + row.getPositionX() + ":" + row.getHealth())
        .containsExactly("PlayerA:false:-532:101");
  }

  @Test
  void replacesCurrentStateForSameExternalPlayerWhenEntityIdChanges() throws Exception {
    Path firstLpLog = tempDir.resolve("first-lp-log");
    Files.writeString(firstLpLog, """
        2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=101, PlayerA, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=101, deaths=0, zombies=8, players=0, score=8, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=7
        Total of 1 in the game
        """);
    Path secondLpLog = tempDir.resolve("second-lp-log");
    Files.writeString(secondLpLog, """
        2026-07-26T11:03:10 11055.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864
        0. id=303, PlayerA, pos=(-520.0, 49.0, -430.1), rot=(-4.2, 369.8, 0.0), remote=True, health=77, deaths=1, zombies=9, players=0, score=9, level=3, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=11
        Total of 1 in the game
        """);

    logImportService.importLogFile(firstLpLog);
    logImportService.importLogFile(secondLpLog);

    assertThat(playerCurrentStateRepository.findAll())
        .extracting(row -> row.getPlayerEntityId() + ":" + row.getPlayerName() + ":" + row.getHealth() + ":" + row.getLevel()
            + ":" + row.getPositionX() + ":" + row.getPing() + ":" + row.isOnline() + ":" + row.getPlayerId())
        .containsExactly("303:PlayerA:77:3:-520:11:true:" + playerRepository.findByPlayerKey("EOS:a").orElseThrow().getId());
  }

  @Test
  void importsRawTelnetPlayerListWithoutGameLogTimestamp() {
    List<String> telnetLines = SevenDaysTelnetService.normalizeLpOutput(List.of(
        "lp",
        "Executing command 'lp' by Telnet from 172.18.0.1:32864",
        "0. id=101, PlayerA, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=76, deaths=1, zombies=8, players=0, score=8, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=7",
        "Total of 1 in the game"
    ), LocalDateTime.of(2026, 7, 26, 10, 53, 10));

    GameLogImportResult result = logImportService.importLogLines("telnet:lp", telnetLines);

    assertThat(result.malformedLines()).isZero();
    assertThat(playerCurrentStateRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.getHealth() + ":" + row.getDeaths() + ":" + row.isOnline()
            + ":" + row.getPlayerId())
        .containsExactly("PlayerA:76:1:true:" + playerRepository.findByPlayerKey("EOS:a").orElseThrow().getId());
  }

  @Test
  void importsPlayerDeathAsTimelineWorldEvent() {
    GameLogImportResult result = logImportService.importLogLines("death.log", List.of(
        "2026-07-31T23:09:00 100.000 INF GMSG: Player 'PlayerA' died",
        "2026-07-31T23:10:00 160.000 INF GMSG: Player 'PlayerB' killed by 'PlayerA'"
    ));

    assertThat(result.worldEvents()).isEqualTo(2);
    assertThat(worldEventRepository.findAll())
        .extracting(row -> row.getEventType() + ":" + row.getActorPlayerName() + ":" + row.getDetailText())
        .containsExactlyInAnyOrder(
            "PLAYER_DEATH:PlayerA:null",
            "PLAYER_DEATH:PlayerB:PlayerA");
  }

  @Test
  void importsRawTelnetPlayerListWithMultiplePlayersAfterOneCommandLine() {
    List<String> telnetLines = SevenDaysTelnetService.normalizeLpOutput(List.of(
        "lp",
        "Executing command 'lp' by Telnet from 172.18.0.1:32864",
        "0. id=101, PlayerA, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=76, deaths=1, zombies=8, players=0, score=8, level=2, pltfmid=Steam_a, crossid=EOS_a, ip=10.0.0.1, ping=7",
        "1. id=202, PlayerB, pos=(10.0, 55.0, 20.1), rot=(0.0, 180.0, 0.0), remote=True, health=99, deaths=0, zombies=2, players=0, score=2, level=1, pltfmid=Steam_b, crossid=EOS_b, ip=10.0.0.2, ping=12",
        "2. id=303, PlayerC, pos=(30.0, 60.0, -40.1), rot=(1.0, 90.0, 0.0), remote=True, health=45, deaths=2, zombies=12, players=0, score=12, level=4, pltfmid=Steam_c, crossid=EOS_c, ip=10.0.0.3, ping=20",
        "Total of 3 in the game"
    ), LocalDateTime.of(2026, 7, 26, 10, 53, 10));

    GameLogImportResult result = logImportService.importLogLines("telnet:lp", telnetLines);

    assertThat(result.malformedLines()).isZero();
    assertThat(playerCurrentStateRepository.findAll())
        .extracting(row -> row.getPlayerName() + ":" + row.getHealth() + ":" + row.getPositionX()
            + ":" + row.getPositionZ() + ":" + row.isOnline())
        .containsExactlyInAnyOrder(
            "PlayerA:76:-532:-446:true",
            "PlayerB:99:10:20:true",
            "PlayerC:45:30:-40:true");
  }

  @Test
  void importsObservedMultiPlayerTelnetPlayerListAndLinksMasterPlayers() {
    GameLogImportResult result = logImportService.importLogLines("telnet:lp", List.of(
        "lp",
        "2026-07-27T11:31:26 3921.931 INF Executing command 'lp' by Telnet from 172.18.0.1:40132",
        "0. id=171, 魅惑のこし餡ぼでぃ, pos=(581.7, 40.0, -538.9), rot=(-46.4, -73.1, 0.0), remote=True, health=115, deaths=1, zombies=226, players=0, score=196, level=15, pltfmid=Steam_76561198382915826, crossid=EOS_00024b5c4d2546468b7c6775bd927c32, ip=219.107.140.192, ping=5",
        "1. id=485, hosi42861, pos=(545.7, 38.9, -549.8), rot=(-2.3, -319.7, 0.0), remote=True, health=108, deaths=1, zombies=16, players=0, score=11, level=8, pltfmid=Steam_76561199276022302, crossid=EOS_0002d3425415470e9632296116cbcc0d, ip=122.131.33.98, ping=5",
        "Total of 2 in the game"
    ));

    assertThat(result.playerListPositions()).isEqualTo(2);
    assertThat(playerRepository.findAll())
        .extracting(player -> player.getPlayerKey() + ":" + player.getPlayerName())
        .containsExactlyInAnyOrder(
            "EOS:00024b5c4d2546468b7c6775bd927c32:魅惑のこし餡ぼでぃ",
            "EOS:0002d3425415470e9632296116cbcc0d:hosi42861");
    assertThat(playerCurrentStateRepository.findAll())
        .allSatisfy(currentState -> assertThat(currentState.getPlayerId()).isNotNull())
        .extracting(row -> row.getPlayerName() + ":" + row.getHealth() + ":" + row.getLevel() + ":" + row.isOnline())
        .containsExactlyInAnyOrder(
            "魅惑のこし餡ぼでぃ:115:15:true",
            "hosi42861:108:8:true");
  }

  private Path writeLog(String content) throws Exception {
    Path file = tempDir.resolve("log");
    Files.writeString(file, content);
    return file;
  }
}
