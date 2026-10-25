package com.yuki.rubinco.luminite.ingest;

import com.yuki.rubinco.luminite.ingest.TerrariaWorld.ContainerKind;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The per-snapshot record stored as extracted_json. Bulky lists (chest contents, bestiary,
 * signs) live in their own tables; this keeps the counts and world facts for history.
 */
public record TerrariaSummary(
    String contentHash,
    World world,
    Clock clock,
    Events events,
    Map<String, Boolean> flags,
    Tiles tiles,
    List<Npc> townNpcs,
    List<PylonEntry> pylons,
    Totals totals) {

  public record World(String title, String seed, int worldId, String worldGuid, int tilesWide,
      int tilesHigh, int gameMode, boolean crimson, boolean hardMode, Instant createdAt,
      List<String> worldTypes, int spawnX, int spawnY, int dungeonX, int dungeonY,
      double groundLevel, double rockLevel, Map<String, Integer> oreTiers,
      int shadowOrbsSmashed, int altarsSmashed) { }

  /** Volatile values at save time; excluded from the content hash. */
  public record Clock(Instant lastPlayedAt, double time, boolean dayTime, int moonPhase,
      boolean raining) { }

  public record Events(boolean bloodMoon, boolean eclipse, boolean sandstorm, boolean slimeRain,
      boolean lanternNight, boolean party, int invasionType, int invasionSize,
      int invasionSizeStart, int anglerQuestItemId, List<String> anglersFinishedToday) { }

  public record Tiles(long activeTiles, long wallTiles, long waterTiles, long lavaTiles,
      long honeyTiles, long shimmerTiles, long redWires, long blueWires, long greenWires,
      long yellowWires, long actuators, long paintedTiles, long paintedWalls, int hallowPercent,
      int corruptionPercent, int crimsonPercent, Map<Integer, Long> resources,
      Map<String, Integer> objects) { }

  public record Npc(int netId, String name, boolean homeless, int homeX, int homeY) { }

  public record PylonEntry(Integer style, int x, int y) { }

  public record Totals(Map<String, Integer> containersByKind, int filledSlots, int distinctItems,
      long storedItems, long bestiaryKills, int bestiaryKilledSpecies, int bestiarySighted,
      int bestiaryChatted, int signs, int tombstones, int rooms, int savedMobs,
      int trainingDummies, int logicSensors, int pressurePlates, int shimmeredTownNpcs,
      Map<String, Integer> managedByKind, int storageRule) { }

  public static TerrariaSummary from(TerrariaWorld world, String contentHash) {
    var h = world.header();
    var t = world.tiles();
    Map<Integer, Long> resources = new TreeMap<>();
    TerrariaCatalog.RESOURCES.forEach(resource -> resources.put(resource.tileId(), t.count(resource.tileId())));
    Map<String, Integer> byKind = new LinkedHashMap<>();
    for (ContainerKind kind : ContainerKind.values()) {
      int count = (int) world.containers().stream().filter(c -> c.kind() == kind).count();
      if (count > 0) byKind.put(kind.name(), count);
    }
    var managed = TerrariaStorage.managed(world);
    Map<String, Integer> managedByKind = new LinkedHashMap<>();
    for (ContainerKind kind : ContainerKind.values()) {
      int count = (int) world.containers().stream().filter(c -> c.kind() == kind && managed.test(c)).count();
      if (count > 0) managedByKind.put(kind.name(), count);
    }
    Map<Integer, Long> items = itemTotals(world);
    return new TerrariaSummary(contentHash,
        new World(h.title(), h.seed(), h.worldId(), h.worldGuid(), h.tilesWide(), h.tilesHigh(),
            h.gameMode(), h.crimson(), h.hardMode(), h.createdAt(),
            h.worldTypes().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList(),
            h.spawnX(), h.spawnY(), h.dungeonX(), h.dungeonY(), h.groundLevel(), h.rockLevel(),
            h.oreTiers(), h.shadowOrbCount(), h.altarCount()),
        new Clock(h.lastPlayedAt(), h.time(), h.dayTime(), h.moonPhase(), h.raining()),
        new Events(h.bloodMoon(), h.eclipse(), h.sandstorm(), h.slimeRain(), h.lanternNight(),
            h.partyActive(), h.invasionType(), h.invasionSize(), h.invasionSizeStart(),
            TerrariaCatalog.anglerQuestItem(h.anglerQuest()), h.anglersFinishedToday()),
        withHardMode(h.flags(), h.hardMode()),
        new Tiles(t.activeTiles(), t.wallTiles(), t.waterTiles(), t.lavaTiles(), t.honeyTiles(),
            t.shimmerTiles(), t.redWires(), t.blueWires(), t.greenWires(), t.yellowWires(),
            t.actuators(), t.paintedTiles(), t.paintedWalls(), t.percent(t.alignmentGood()),
            t.percent(t.alignmentEvil()), t.percent(t.alignmentBlood()), resources, t.objectCounts()),
        world.townNpcs().stream().map(npc -> new Npc(npc.netId(), npc.name(), npc.homeless(),
            npc.homeX(), npc.homeY())).toList(),
        world.pylons().stream().map(pylon -> new PylonEntry(pylon.style(), pylon.x(), pylon.y())).toList(),
        new Totals(byKind,
            world.containers().stream().filter(managed).mapToInt(c -> c.items().size()).sum(), items.size(),
            items.values().stream().mapToLong(Long::longValue).sum(),
            world.bestiaryKills().values().stream().mapToLong(Integer::longValue).sum(),
            (int) world.bestiaryKills().values().stream().filter(kills -> kills > 0).count(),
            world.bestiarySighted().size(), world.bestiaryChatted().size(), world.signs().size(),
            (int) world.signs().stream().filter(sign -> Integer.valueOf(85).equals(sign.tileType())).count(),
            world.rooms().size(), world.savedMobs(), world.trainingDummies(), world.logicSensors(),
            world.pressurePlates(), world.shimmeredTownNpcs().size(), managedByKind,
            TerrariaStorage.RULE_VERSION));
  }

  /** Total stack per item id across managed storage (TerrariaStorage). */
  public static Map<Integer, Long> itemTotals(TerrariaWorld world) {
    Map<Integer, Long> totals = new TreeMap<>();
    world.containers().stream().filter(TerrariaStorage.managed(world)).forEach(container -> container.items().forEach(item ->
        totals.merge(item.itemId(), (long) item.stack(), Long::sum)));
    return totals;
  }

  private static Map<String, Boolean> withHardMode(Map<String, Boolean> flags, boolean hardMode) {
    Map<String, Boolean> result = new LinkedHashMap<>(flags);
    result.put("HardMode", hardMode);
    return result;
  }
}
