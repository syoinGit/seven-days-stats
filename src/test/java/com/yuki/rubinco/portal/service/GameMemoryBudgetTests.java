package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuki.rubinco.portal.config.GameCatalogProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameMemoryBudgetTests {
  private final GameMemoryBudget budget = budget(2560);

  static GameMemoryBudget budget(int total) {
    var properties = new GameCatalogProperties();
    properties.setMemoryBudgetMib(total);
    properties.setCatalog(List.of(game("7dtd", 4096), game("minecraft", 2048),
        game("corekeeper", 1536), game("terraria", 1024)));
    properties.getCatalog().get(3).setManualStart(false);
    return new GameMemoryBudget(properties);
  }

  private static GameCatalogProperties.Game game(String id, int memory) {
    var game = new GameCatalogProperties.Game();
    game.setId(id);
    game.setMemoryMib(memory);
    return game;
  }

  @Test
  void automaticTerrariaCountsAndExactBudgetIsAllowed() {
    var result = budget.evaluate(Map.of("7dtd", "offline", "minecraft", "offline",
        "corekeeper", "offline", "terraria", "online"));
    assertThat(result.reservedMiB()).isEqualTo(1024);
    assertThat(result.allocations().get("corekeeper").startAllowed()).isTrue();
    assertThat(result.allocations().get("minecraft").startAllowed()).isFalse();
    assertThat(result.allocations().get("7dtd").reason()).contains("上限");
    assertThat(result.allocations().get("terraria").startAllowed()).isFalse();
  }

  @Test
  void startupReservesTheFullAllocationAndBlocksAnotherGame() {
    var result = budget.evaluate(Map.of("7dtd", "offline", "minecraft", "starting",
        "corekeeper", "offline", "terraria", "offline"));
    assertThat(result.reservedMiB()).isEqualTo(2048);
    assertThat(result.allocations().get("corekeeper").startAllowed()).isFalse();
  }

  @Test
  void unknownMissingAndErrorStatesBlockStartsRatherThanAssumingFreeMemory() {
    for (String status : List.of("error", "unknown", "unexpected")) {
      var result = budget.evaluate(Map.of("7dtd", "offline", "minecraft", "offline",
          "corekeeper", status, "terraria", "offline"));
      assertThat(result.known()).isFalse();
      assertThat(result.allocations().values()).allMatch(allocation -> !allocation.startAllowed());
    }
    assertThat(budget.evaluate(Map.of()).known()).isFalse();
  }

  @Test
  void stoppingAGameReleasesItsReservationAndOverBudgetExistingGamesAreCounted() {
    var full = budget.evaluate(Map.of("7dtd", "online", "minecraft", "offline",
        "corekeeper", "offline", "terraria", "online"));
    assertThat(full.reservedMiB()).isEqualTo(5120);
    assertThat(full.allocations().get("minecraft").startAllowed()).isFalse();
    var stopped = budget.evaluate(Map.of("7dtd", "offline", "minecraft", "offline",
        "corekeeper", "offline", "terraria", "offline"));
    assertThat(stopped.reservedMiB()).isZero();
    assertThat(stopped.allocations().get("minecraft").startAllowed()).isTrue();
  }
}
