package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DockerGameServerStartGatewayTests {
  private final DockerCommandRunner runner = mock(DockerCommandRunner.class);
  private final DockerGameServerStartGateway gateway = new DockerGameServerStartGateway(
      runner, new ObjectMapper());

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
}
