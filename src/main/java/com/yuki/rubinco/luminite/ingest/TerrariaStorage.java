package com.yuki.rubinco.luminite.ingest;

import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Managed storage: containers near a town NPC's home or a pylon, which is where players keep
 * their bases. World-generated chests in caves and dungeons are left out of item totals.
 */
public final class TerrariaStorage {
  /**
   * Tiles from a home or pylon on each axis. On a real world, every limit from 30 to 60 tiles
   * picked the same 66 of 348 chests; past that, lone cave chests start to join.
   */
  public static final int RANGE = 50;
  /** Bump when the rule changes: the next import then re-baselines item totals silently. */
  public static final int RULE_VERSION = 1;

  private TerrariaStorage() {
  }

  public static Predicate<Container> managed(TerrariaWorld world) {
    List<int[]> anchors = new ArrayList<>();
    world.townNpcs().stream().filter(npc -> !npc.homeless())
        .forEach(npc -> anchors.add(new int[] {npc.homeX(), npc.homeY()}));
    world.pylons().forEach(pylon -> anchors.add(new int[] {pylon.x(), pylon.y()}));
    return container -> anchors.stream().anyMatch(anchor ->
        Math.abs(anchor[0] - container.x()) <= RANGE && Math.abs(anchor[1] - container.y()) <= RANGE);
  }
}
