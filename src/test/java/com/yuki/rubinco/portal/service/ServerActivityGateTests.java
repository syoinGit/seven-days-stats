package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.yuki.rubinco.portal.config.GameCatalogProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerActivityGateTests {
  private final GameServerStartGateway gateway = mock(GameServerStartGateway.class);
  private final ServerActivityGate gate = new ServerActivityGate(gateway, catalog());

  @Test
  void stoppedServerGetsOneCatchUpImportPerJobAndNoAiPosts() {
    doReturn(true).when(gateway).liveStatus();
    status("online");
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.aiAllowed("corekeeper")).isTrue();

    status("offline");
    assertThat(gate.aiAllowed("corekeeper")).isFalse();
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.importAllowed("ck", "corekeeper")).isFalse();
    assertThat(gate.importAllowed("other-job", "corekeeper")).isTrue();
    assertThat(gate.importAllowed("other-job", "corekeeper")).isFalse();

    status("starting");
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.aiAllowed("corekeeper")).isFalse();
    status("offline");
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.importAllowed("ck", "corekeeper")).isFalse();
  }

  @Test
  void unreadableStatusKeepsImportsButStopsAi() {
    doReturn(true).when(gateway).liveStatus();
    status("error");
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.aiAllowed("corekeeper")).isFalse();
  }

  @Test
  void withoutLiveStatusNothingIsPaused() {
    doReturn(false).when(gateway).liveStatus();
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.importAllowed("ck", "corekeeper")).isTrue();
    assertThat(gate.aiAllowed("corekeeper")).isTrue();
  }

  private void status(String status) {
    doReturn(Map.of("corekeeper", status)).when(gateway).currentStatuses(anyList());
  }

  private static GameCatalogProperties catalog() {
    var properties = new GameCatalogProperties();
    var game = new GameCatalogProperties.Game();
    game.setId("corekeeper");
    properties.setCatalog(List.of(game));
    return properties;
  }
}
