package com.yuki.rubinco.octarine.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Connected;
import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Disconnected;
import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Event;
import com.yuki.rubinco.octarine.session.CoreKeeperLogParser.Renamed;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoreKeeperLogParserTests {
  static final String STEAM = "76561198000000001";

  /** The order the dedicated server writes one connection in (SteamID replaced). */
  static List<String> connection(String steamId, String userId, String name, String start, String end) {
    return List.of(
        start + " Accepted connection from " + steamId + " with result OK awaiting authentication",
        start + " SteamNet Warning: [#" + userId + " P2P steamid:" + steamId + " vport 0] Relay candidates enabled",
        start + " Connected to userid:" + userId,
        start + " Successful authentication from userid: " + steamId,
        start + " timescale = 1",
        start + " [userid:" + userId + "] player " + name + " connected islocalplayer=False",
        start + " [userid:" + userId + "] is using new name " + name,
        end + " Disconnected from userid:" + userId + " with reason App_Min",
        end + " player disconnect");
  }

  @Test
  void readsConnectRenameAndDisconnectWithDockerTimes() {
    var parser = new CoreKeeperLogParser();
    List<Event> events = connection(STEAM, "1000000001", "Explorer",
        "2026-10-25T11:00:00.123456789Z", "2026-10-25T12:30:00.000000001Z").stream()
        .map(parser::accept).flatMap(Optional::stream).toList();
    assertThat(events).containsExactly(
        new Connected(STEAM, "Explorer", OffsetDateTime.parse("2026-10-25T11:00:00.123456789Z")),
        new Renamed(STEAM, "Explorer", OffsetDateTime.parse("2026-10-25T11:00:00.123456789Z")),
        new Disconnected(STEAM, "App_Min", OffsetDateTime.parse("2026-10-25T12:30:00.000000001Z")));
  }

  @Test
  void pairsTheSteamIdWithoutTheP2pLineAndSkipsUnknownConnections() {
    var parser = new CoreKeeperLogParser();
    assertThat(parser.accept("2026-10-25T11:00:00Z Accepted connection from " + STEAM + " with result OK")).isEmpty();
    assertThat(parser.accept("2026-10-25T11:00:00Z Connected to userid:42")).isEmpty();
    assertThat(parser.accept("2026-10-25T11:00:01Z [userid:42] player A connected islocalplayer=False"))
        .contains(new Connected(STEAM, "A", OffsetDateTime.parse("2026-10-25T11:00:01Z")));
    assertThat(parser.accept("2026-10-25T11:00:01Z [userid:43] player B connected islocalplayer=False")).isEmpty();
    assertThat(parser.accept("2026-10-25T11:00:02Z Disconnected from userid:43 with reason App_Min")).isEmpty();
  }

  @Test
  void ignoresLinesWithoutDockerTimestampsOrWithAFakeSteamId() {
    var parser = new CoreKeeperLogParser();
    assertThat(parser.accept("Accepted connection from " + STEAM + " with result OK")).isEmpty();
    assertThat(parser.accept("2026-10-25T11:00:00Z Accepted connection from 12345 with result OK")).isEmpty();
    assertThat(parser.accept("2026-10-25T11:00:00Z Connected to userid:7")).isEmpty();
    assertThat(parser.accept("2026-10-25T11:00:00Z [userid:7] player X connected islocalplayer=False")).isEmpty();
    assertThat(parser.accept(null)).isEmpty();
  }

  @Test
  void cleansPlayerWrittenNames() {
    assertThat(CoreKeeperLogParser.name("  a\tb\u0007 ")).isEqualTo("a b");
    assertThat(CoreKeeperLogParser.name(" \u0001 ")).isNull();
    assertThat(CoreKeeperLogParser.name("x".repeat(200))).hasSize(CoreKeeperLogParser.MAX_NAME_LENGTH);
    assertThat(CoreKeeperLogParser.name("x".repeat(119) + "😀")).hasSize(119);
  }

  @Test
  void formatsDurationsInJapanese() {
    assertThat(CoreKeeperSessionQueryService.duration(java.time.Duration.ofMinutes(205))).isEqualTo("3時間25分");
    assertThat(CoreKeeperSessionQueryService.duration(java.time.Duration.ofMinutes(120))).isEqualTo("2時間");
    assertThat(CoreKeeperSessionQueryService.duration(java.time.Duration.ofMinutes(12))).isEqualTo("12分");
    assertThat(CoreKeeperSessionQueryService.duration(java.time.Duration.ofSeconds(20))).isEqualTo("1分未満");
    assertThat(CoreKeeperSessionQueryService.duration(java.time.Duration.ZERO)).isEqualTo("0分");
  }
}
