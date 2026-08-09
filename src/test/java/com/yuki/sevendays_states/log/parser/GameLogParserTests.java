package com.yuki.sevendays_states.log.parser;

import com.yuki.sevendays_states.log.dto.EntityKillLogEvent;
import com.yuki.sevendays_states.log.dto.LevelXpSummaryLogEvent;
import com.yuki.sevendays_states.log.dto.PlayerJoinLogEvent;
import com.yuki.sevendays_states.log.dto.PlayerLeaveLogEvent;
import com.yuki.sevendays_states.log.dto.PlayerListPositionLogEvent;
import com.yuki.sevendays_states.log.dto.ServerMetricLogEvent;
import com.yuki.sevendays_states.log.dto.SleeperLogEvent;
import com.yuki.sevendays_states.log.dto.VehicleLogEvent;
import com.yuki.sevendays_states.log.dto.WorldEventLogEvent;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameLogParserTests {

  @Test
  void parsesPlayerJoin() {
    PlayerJoinLogEvent event = new PlayerJoinLogParser().parse(
        "2026-07-26T08:18:02 1147.256 INF PlayerSpawnedInWorld (reason: JoinMultiplayer, position: -162, 52, -857): EntityID=331, PltfmId='Steam_76561198123350583', CrossId='EOS_xxx', OwnerID='Steam_xxx', PlayerName='DDD烈火王テムジン', ClientNumber='3'")
        .orElseThrow();

    assertThat(event.playerName()).isEqualTo("DDD烈火王テムジン");
    assertThat(event.playerEntityId()).isEqualTo(331);
    assertThat(event.platformId()).isEqualTo("Steam_76561198123350583");
    assertThat(event.crossPlatformId()).isEqualTo("EOS_xxx");
    assertThat(event.positionX()).isEqualTo(-162);
    assertThat(event.positionY()).isEqualTo(52);
    assertThat(event.positionZ()).isEqualTo(-857);
    assertThat(event.reason()).isEqualTo("JoinMultiplayer");
    assertThat(event.clientNumber()).isEqualTo(3);
  }

  @Test
  void parsesPlayerLeave() {
    PlayerLeaveLogEvent event = new PlayerLeaveLogParser().parse(
        "2026-07-26T08:41:32 2557.179 INF Player disconnected: EntityID=331, PltfmId='Steam_76561198123350583', CrossId='EOS_xxx', OwnerID='Steam_xxx', PlayerName='DDD烈火王テムジン', ClientNumber='3'")
        .orElseThrow();

    assertThat(event.playerName()).isEqualTo("DDD烈火王テムジン");
    assertThat(event.playerEntityId()).isEqualTo(331);
    assertThat(event.platformId()).isEqualTo("Steam_76561198123350583");
    assertThat(event.crossPlatformId()).isEqualTo("EOS_xxx");
    assertThat(event.clientNumber()).isEqualTo(3);
  }

  @Test
  void parsesEntityKillWithJapanesePlayerName() {
    EntityKillLogEvent event = new EntityKillLogParser().parse(
        "2026-07-26T08:22:51 1436.863 INF Entity zombieBusinessMan 347 killed by DDD烈火王テムジン 331")
        .orElseThrow();

    assertThat(event.playerName()).isEqualTo("DDD烈火王テムジン");
    assertThat(event.playerEntityId()).isEqualTo(331);
    assertThat(event.targetEntityType()).isEqualTo("zombieBusinessMan");
    assertThat(event.targetEntityId()).isEqualTo(347);
  }

  @Test
  void parsesEntityKillWithSpacesAndSymbolsInPlayerName() {
    EntityKillLogEvent event = new EntityKillLogParser().parse(
        "2026-07-26T08:22:51 1436.863 INF Entity zombieBoe 358 killed by 日本語 Player!? 331")
        .orElseThrow();

    assertThat(event.playerName()).isEqualTo("日本語 Player!?");
    assertThat(event.playerEntityId()).isEqualTo(331);
  }

  @Test
  void parsesLevelXpSummaryBlock() {
    LevelXpSummaryLogEvent event = new LevelXpSummaryLogParser().parse(List.of(
        "2026-07-26T08:36:07 2233.109 INF MinEventLogMessage: XP gained during the last level:",
        "2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromLootThisLevel == 16",
        "2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromHarvestingThisLevel == 1014",
        "2026-07-26T08:36:07 2233.109 INF CVarLogValue: $xpFromKillThisLevel == 3950"), 0)
        .orElseThrow();

    assertThat(event.xpFromLoot()).isEqualTo(16);
    assertThat(event.xpFromHarvesting()).isEqualTo(1014);
    assertThat(event.xpFromKill()).isEqualTo(3950);
    assertThat(event.xpTotal()).isEqualTo(4980);
    assertThat(event.consumedLineCount()).isEqualTo(4);
  }

  @Test
  void parsesSleeperSpawn() {
    SleeperLogEvent event = new SleeperLogParser().parse(
        "2026-07-26T08:24:51 1556.661 INF 1544.871 SleeperVolume -546, 55, -577: Spawning -538, 55, -570 (-34, -36), group 'sleeperHordeStageGS2', class zombieBoe, count 5")
        .orElseThrow();

    assertThat(event.transactionType()).isEqualTo("SLEEPER_SPAWN");
    assertThat(event.sleeperVolumeX()).isEqualTo(-546);
    assertThat(event.positionX()).isEqualTo(-538);
    assertThat(event.chunkX()).isEqualTo(-34);
    assertThat(event.sleeperGroup()).isEqualTo("sleeperHordeStageGS2");
    assertThat(event.entityClass()).isEqualTo("zombieBoe");
    assertThat(event.entityCount()).isEqualTo(5);
  }

  @Test
  void parsesSleeperRestore() {
    SleeperLogEvent event = new SleeperLogParser().parse(
        "2026-07-26T08:21:24 1349.173 INF 1337.678 SleeperVolume -151, 38, -767: Restoring -144, 39, -765 (-9, -48) 'zombieSteveCrawler', count 0")
        .orElseThrow();

    assertThat(event.transactionType()).isEqualTo("SLEEPER_RESTORE");
    assertThat(event.sleeperVolumeX()).isEqualTo(-151);
    assertThat(event.positionX()).isEqualTo(-144);
    assertThat(event.chunkZ()).isEqualTo(-48);
    assertThat(event.sleeperGroup()).isNull();
    assertThat(event.entityClass()).isEqualTo("zombieSteveCrawler");
    assertThat(event.entityCount()).isZero();
  }

  @Test
  void parsesServerMetric() {
    ServerMetricLogEvent event = new ServerMetricLogParser().parse(
        "2026-07-26T08:23:58 1503.860 INF Time: 24.87m FPS: 20.00 Heap: 1708.8MB Max: 1715.6MB Chunks: 249 CGO: 27 Ply: 1 Zom: 3 Ent: 5 (16) Items: 0 CO: 1 RSS: 2806.8MB")
        .orElseThrow();

    assertThat(event.uptimeMinutes()).isEqualByComparingTo(new BigDecimal("24.87"));
    assertThat(event.fps()).isEqualByComparingTo(new BigDecimal("20.00"));
    assertThat(event.heapMb()).isEqualByComparingTo(new BigDecimal("1708.8"));
    assertThat(event.maxHeapMb()).isEqualByComparingTo(new BigDecimal("1715.6"));
    assertThat(event.chunks()).isEqualTo(249);
    assertThat(event.cgo()).isEqualTo(27);
    assertThat(event.playerCount()).isEqualTo(1);
    assertThat(event.zombieCount()).isEqualTo(3);
    assertThat(event.entityCount()).isEqualTo(5);
    assertThat(event.entityCountDetail()).isEqualTo(16);
    assertThat(event.itemCount()).isZero();
    assertThat(event.co()).isEqualTo(1);
    assertThat(event.rssMb()).isEqualByComparingTo(new BigDecimal("2806.8"));
  }

  @Test
  void parsesAirDropSupplyCrate() {
    WorldEventLogEvent event = new AirDropLogParser().parse(
        "2026-07-29T14:07:38 11342.887 INF AIAirDrop: Spawned supply crate at (460.2, 209.1, 32.6), plane is at (461.73, 219.09, 38.19)")
        .orElseThrow();

    assertThat(event.eventType()).isEqualTo("AIR_DROP");
    assertThat(event.positionX()).isEqualTo(460);
    assertThat(event.positionY()).isEqualTo(209);
    assertThat(event.positionZ()).isEqualTo(33);
  }

  @Test
  void parsesAiDirectorEvents() {
    AiDirectorLogParser parser = new AiDirectorLogParser();

    WorldEventLogEvent horde = parser.parse(
        "2026-07-29T14:09:23 11448.316 INF AIDirector: FindWanderingTargets at player '[type=EntityPlayer, name=hosi42861, id=485]', dist 55.58979")
        .orElseThrow();
    WorldEventLogEvent scout = parser.parse(
        "2026-07-29T14:23:01 12265.980 INF AIDirector: Spawning Scouts2 at (446.0, 39.0, -701.0), to (437.0, 40.0, -621.0)")
        .orElseThrow();
    WorldEventLogEvent screamer = parser.parse(
        "2026-07-29T14:23:01 12266.015 INF Spawned [type=EntityZombie, name=zombieScreamer, id=4601] at (447.5, 39.0, -706.5) Day=13 TotalInWave=1 CurrentWave=1")
        .orElseThrow();

    assertThat(horde.eventType()).isEqualTo("WANDERING_HORDE");
    assertThat(horde.actorPlayerName()).isEqualTo("hosi42861");
    assertThat(horde.actorPlayerEntityId()).isEqualTo(485);
    assertThat(scout.eventType()).isEqualTo("SCOUT_HORDE");
    assertThat(scout.targetPositionZ()).isEqualTo(-621);
    assertThat(screamer.eventType()).isEqualTo("SCREAMER_SPAWN");
    assertThat(screamer.positionX()).isEqualTo(448);
  }

  @Test
  void parsesBloodMoonAndVehicleEvents() {
    WorldEventLogEvent bloodMoon = new BloodMoonLogParser().parse(
        "2026-07-30T10:57:36 36.485 INF BloodMoon SetDay: day 14, last day 7, freq 7, range 0")
        .orElseThrow();
    VehicleLogParser parser = new VehicleLogParser();
    VehicleLogEvent loaded = parser.parse(
        "2026-07-29T13:56:11 10656.556 INF VehicleManager loaded #0, id 2631, [type=EntityBicycle, name=vehicleBicycle, id=2631], (442.2, 38.0, -615.0), chunk 27, -39 (27, -39), owner EOS_00024b5c4d2546468b7c6775bd927c32")
        .orElseThrow();
    VehicleLogEvent write = parser.parse(
        "2026-07-29T13:40:42 9726.680 INF 219671 VehicleManager write #0, id 3718, vehicleBicycle, (157.4, 38.0, -733.3), chunk 9, -46")
        .orElseThrow();
    VehicleLogEvent truck = parser.parse(
        "2026-07-29T13:40:43 9727.680 INF VehicleManager loaded #0, id 4001, [type=EntityVJeep, name=vehicleTruck4x4, id=4001], (157.4, 38.0, -733.3), chunk 9, -46 (9, -46), owner EOS_truck")
        .orElseThrow();
    VehicleLogEvent removed = parser.parse(
        "2026-07-29T14:21:58 12203.592 INF VehicleManager RemoveTrackedVehicle [type=EntityBicycle, name=vehicleBicycle, id=3718], Killed")
        .orElseThrow();

    assertThat(bloodMoon.eventType()).isEqualTo("BLOOD_MOON");
    assertThat(bloodMoon.detailText()).isEqualTo("Day 14 / 周期 7");
    assertThat(loaded.eventType()).isEqualTo("VEHICLE_LOADED");
    assertThat(loaded.ownerCrossPlatformId()).isEqualTo("EOS_00024b5c4d2546468b7c6775bd927c32");
    assertThat(write.eventType()).isEqualTo("VEHICLE_WRITE");
    assertThat(write.positionX()).isEqualTo(157);
    assertThat(truck.vehicleType()).isEqualTo("EntityVJeep");
    assertThat(truck.vehicleName()).isEqualTo("vehicleTruck4x4");
    assertThat(truck.ownerCrossPlatformId()).isEqualTo("EOS_truck");
    assertThat(removed.eventType()).isEqualTo("VEHICLE_REMOVED");
    assertThat(removed.removalReason()).isEqualTo("Killed");
  }

  @Test
  void parsesPlayerListPositionBlock() {
    PlayerListPositionLogEvent event = new PlayerListPositionLogParser().parse(List.of(
        "2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864",
        "0. id=331, DDD烈火王テムジン, pos=(-532.0, 48.0, -446.1), rot=(-4.2, 369.8, 0.0), remote=True, health=101, deaths=0, zombies=8, players=0, score=8, level=2, pltfmid=Steam_76561198123350583, crossid=EOS_00027c24c9be4607b57f74c1689fa7c5, ip=218.231.71.221, ping=7",
        "Total of 1 in the game",
        "2026-07-26T10:53:11 10457.088 INF Time: 173.91m FPS: 20.00 Heap: 1666.4MB Max: 1719.4MB Chunks: 264 CGO: 9 Ply: 1 Zom: 0 Ent: 1 (21) Items: 0 CO: 1 RSS: 2758.5MB"), 0)
        .orElseThrow();

    assertThat(event.consumedLineCount()).isEqualTo(3);
    assertThat(event.totalPlayerCount()).isEqualTo(1);
    assertThat(event.players()).hasSize(1);
    assertThat(event.players().getFirst().playerEntityId()).isEqualTo(331);
    assertThat(event.players().getFirst().playerName()).isEqualTo("DDD烈火王テムジン");
    assertThat(event.players().getFirst().positionX()).isEqualTo(-532);
    assertThat(event.players().getFirst().positionY()).isEqualTo(48);
    assertThat(event.players().getFirst().positionZ()).isEqualTo(-446);
    assertThat(event.players().getFirst().rotationX()).isEqualByComparingTo(new BigDecimal("-4.2"));
    assertThat(event.players().getFirst().health()).isEqualTo(101);
    assertThat(event.players().getFirst().level()).isEqualTo(2);
    assertThat(event.players().getFirst().platformId()).isEqualTo("Steam_76561198123350583");
    assertThat(event.players().getFirst().crossPlatformId()).isEqualTo("EOS_00027c24c9be4607b57f74c1689fa7c5");
    assertThat(event.players().getFirst().ping()).isEqualTo(7);
  }

  @Test
  void parsesCurrentTwoPlayerListPositionBlock() {
    PlayerListPositionLogEvent event = new PlayerListPositionLogParser().parse(List.of(
        "2026-07-30T12:00:31 3811.560 INF Executing command 'lp' by Telnet from 172.18.0.1:47050",
        "0. id=171, 魅惑のこし餡ぼでぃ, pos=(450.6, 38.1, -675.8), rot=(-36.6, 877.5, 0.0), remote=True, health=137, deaths=1, zombies=815, players=0, score=742, level=33, pltfmid=Steam_76561198382915826, crossid=EOS_00024b5c4d2546468b7c6775bd927c32, ip=219.107.140.192, ping=5",
        "1. id=485, hosi42861, pos=(-31.4, 38.1, -705.2), rot=(-28.5, 501.5, 0.0), remote=True, health=125, deaths=1, zombies=162, players=0, score=147, level=25, pltfmid=Steam_76561199276022302, crossid=EOS_0002d3425415470e9632296116cbcc0d, ip=122.131.33.98, ping=5",
        "Total of 2 in the game"), 0)
        .orElseThrow();

    assertThat(event.players()).hasSize(2);
    assertThat(event.players().get(0).playerName()).isEqualTo("魅惑のこし餡ぼでぃ");
    assertThat(event.players().get(0).positionX()).isEqualTo(451);
    assertThat(event.players().get(0).positionY()).isEqualTo(38);
    assertThat(event.players().get(0).positionZ()).isEqualTo(-676);
    assertThat(event.players().get(0).health()).isEqualTo(137);
    assertThat(event.players().get(0).level()).isEqualTo(33);
    assertThat(event.players().get(1).playerName()).isEqualTo("hosi42861");
    assertThat(event.players().get(1).positionX()).isEqualTo(-31);
    assertThat(event.players().get(1).positionZ()).isEqualTo(-705);
    assertThat(event.players().get(1).health()).isEqualTo(125);
  }

  @Test
  void parsesEmptyPlayerListPositionBlock() {
    PlayerListPositionLogEvent event = new PlayerListPositionLogParser().parse(List.of(
        "2026-07-26T10:53:10 10455.738 INF Executing command 'lp' by Telnet from 172.18.0.1:32864",
        "Total of 0 in the game"), 0)
        .orElseThrow();

    assertThat(event.totalPlayerCount()).isZero();
    assertThat(event.players()).isEmpty();
    assertThat(event.consumedLineCount()).isEqualTo(2);
  }

  @Test
  void returnsEmptyForMalformedLog() {
    assertThat(new PlayerJoinLogParser().parse("broken log")).isEmpty();
    assertThat(new ServerMetricLogParser().parse("2026-07-26T08:23:58 1503.860 INF Time: broken")).isEmpty();
  }
}
