package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.yuki.rubinco.portal.config.DockerGameServerProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DockerGameServerStartGatewayTests {
  private final DockerCommandRunner runner = mock(DockerCommandRunner.class);
  private final DockerGameServerStartGateway gateway = new DockerGameServerStartGateway(
      runner, new ObjectMapper(), new DockerGameServerProperties());

  @Test
  void startsOnlyTheFixedContainerForEachManualGame() {
    for (var target : java.util.Map.of("7dtd", "7dtd", "minecraft", "rubinco-minecraft",
        "corekeeper", "rubinco-corekeeper").entrySet()) {
      when(runner.run("status", target.getKey()))
          .thenReturn("{\"Status\":\"exited\"}");
      assertThat(gateway.requestStart(target.getKey(), "member").accepted()).isTrue();
      verify(runner).run("start", target.getKey());
    }
  }

  @Test
  void cannotStartTerrariaDatabaseOrUserSuppliedContainerNames() {
    for (String id : List.of("terraria", "postgres", "seven-days-postgres", "--help", "7dtd; touch /tmp/x")) {
      assertThat(gateway.requestStart(id, "member").accepted()).isFalse();
    }
    verifyNoInteractions(runner);
  }

  @Test
  void runningContainerAndUnavailableDockerNeverReceiveStart() {
    when(runner.run("status", "7dtd"))
        .thenReturn("{\"Status\":\"running\"}")
        .thenThrow(new IllegalStateException("daemon detail"));
    assertThat(gateway.requestStart("7dtd", "member").accepted()).isFalse();
    assertThat(gateway.requestStart("7dtd", "member").status()).isEqualTo("error");
    verify(runner, never()).run("start", "7dtd");
  }

  @Test
  void reportsLiveStatesIncludingDockerHealthWithoutSystemd() {
    when(runner.run("status", "7dtd"))
        .thenReturn("{\"Status\":\"created\"}",
            "{\"Status\":\"running\",\"Health\":{\"Status\":\"starting\"}}",
            "{\"Status\":\"running\",\"Health\":{\"Status\":\"healthy\"}}",
            "{\"Status\":\"running\",\"Health\":{\"Status\":\"unhealthy\"}}",
            "{\"Status\":\"restarting\"}", "{\"Status\":\"paused\"}", "not json");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("offline");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("starting");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("online");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("error");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("starting");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("error");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("error");
  }

  @Test
  void readsAllVisibleServersInOneCommandAndDoesNotGuessMissingStates() {
    when(runner.run("statuses"))
        .thenReturn("terraria {\"Status\":\"running\"}\n7dtd {\"Status\":\"exited\"}");
    assertThat(gateway.currentStatuses(List.of("7dtd", "terraria", "minecraft")))
        .containsEntry("7dtd", "offline").containsEntry("terraria", "online")
        .containsEntry("minecraft", "error");
    verify(runner, times(1)).run(any(String[].class));
  }

  @Test
  void invalidStateDoesNotHideTheOtherGames() {
    when(runner.run("statuses"))
        .thenReturn("7dtd not-json\ncorekeeper {\"Status\":\"running\"}");
    assertThat(gateway.currentStatuses(List.of("7dtd", "corekeeper")))
        .containsEntry("7dtd", "error").containsEntry("corekeeper", "online");
  }

  @Test
  void admissionAlwaysRefreshesEvenWhenTheDisplayCacheSaysOffline() {
    when(runner.run("statuses"))
        .thenReturn("7dtd {\"Status\":\"exited\"}", "7dtd {\"Status\":\"running\"}");
    assertThat(gateway.currentStatuses(List.of("7dtd"))).containsEntry("7dtd", "offline");
    assertThat(gateway.freshStatuses(List.of("7dtd"))).containsEntry("7dtd", "online");
    verify(runner, times(2)).run("statuses");
  }

  @Test
  void stopRunsInTheBackgroundWithTheLongTimeoutAndReportsStoppingUntilDone() throws Exception {
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    when(runner.run("status", "7dtd")).thenReturn("{\"Status\":\"running\"}");
    when(runner.run(any(Duration.class), eq("stop"), eq("7dtd"))).thenAnswer(ignored -> {
      release.await(5, TimeUnit.SECONDS);
      finished.countDown();
      return "";
    });

    var result = gateway.requestStop("7dtd", "member");

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo("stopping");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("stopping");
    assertThat(gateway.requestStart("7dtd", "member").accepted()).isFalse();
    assertThat(gateway.requestStop("7dtd", "member").accepted()).isFalse();
    release.countDown();
    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    verify(runner, timeout(5000)).run(Duration.ofSeconds(150), "stop", "7dtd");
    verify(runner, never()).run("start", "7dtd");
  }

  @Test
  void stopIsLimitedToRunningManualGames() {
    when(runner.run("status", "minecraft")).thenReturn("{\"Status\":\"exited\"}");
    assertThat(gateway.requestStop("minecraft", "member").accepted()).isFalse();
    for (String id : List.of("terraria", "postgres", "7dtd; touch /tmp/x")) {
      assertThat(gateway.requestStop(id, "member").accepted()).isFalse();
    }
    verify(runner, never()).run(any(Duration.class), any(String[].class));
  }

  @Test
  void failedStopReleasesTheStoppingMarker() throws Exception {
    when(runner.run("status", "corekeeper")).thenReturn("{\"Status\":\"running\"}");
    when(runner.run(any(Duration.class), eq("stop"), eq("corekeeper")))
        .thenThrow(new IllegalStateException("Docker command timed out"));
    assertThat(gateway.requestStop("corekeeper", "member").accepted()).isTrue();
    verify(runner, timeout(5000)).run(any(Duration.class), eq("stop"), eq("corekeeper"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!"online".equals(gateway.currentStatus("corekeeper")) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(gateway.currentStatus("corekeeper")).isEqualTo("online");
    assertThat(gateway.controlNotices().get("corekeeper")).contains("停止の完了を確認できませんでした");
  }

  @Test
  void failedStopLeavesANoticeUntilTheServerIsDown() throws Exception {
    failStop("7dtd");
    assertThat(gateway.controlNotices().get("7dtd")).startsWith("停止に失敗しました");

    doReturn("{\"Status\":\"exited\"}").when(runner).run("status", "7dtd");
    assertThat(gateway.currentStatus("7dtd")).isEqualTo("offline");
    assertThat(gateway.controlNotices()).doesNotContainKey("7dtd");
  }

  @Test
  void aNewStopAttemptClearsTheEarlierNotice() throws Exception {
    failStop("minecraft");
    doReturn("").when(runner).run(any(Duration.class), eq("stop"), eq("minecraft"));
    assertThat(gateway.requestStop("minecraft", "member").accepted()).isTrue();
    assertThat(gateway.controlNotices()).doesNotContainKey("minecraft");
  }

  private void failStop(String gameId) throws Exception {
    when(runner.run("status", gameId)).thenReturn("{\"Status\":\"running\"}");
    when(runner.run(any(Duration.class), eq("stop"), eq(gameId)))
        .thenThrow(new IllegalStateException("Docker command failed"));
    assertThat(gateway.requestStop(gameId, "member").accepted()).isTrue();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while ((!gateway.controlNotices().containsKey(gameId) || "stopping".equals(gateway.currentStatus(gameId)))
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }
}
