package com.yuki.rubinco.luminite.ingest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything read from one desktop world file. Values are as saved; nothing is inferred. */
public record TerrariaWorld(
    String sourceSha256,
    int version,
    long fileRevision,
    Header header,
    TileStats tiles,
    List<Container> containers,
    List<Sign> signs,
    List<TownNpc> townNpcs,
    List<Integer> shimmeredTownNpcs,
    int savedMobs,
    List<Pylon> pylons,
    int trainingDummies,
    int logicSensors,
    int pressurePlates,
    List<Room> rooms,
    Map<String, Integer> bestiaryKills,
    Set<String> bestiarySighted,
    Set<String> bestiaryChatted) {

  public record Header(
      String title, String seed, long worldGenVersion, String worldGuid, int worldId,
      int tilesWide, int tilesHigh, int gameMode,
      // Secret seeds and special world types, in save order.
      Map<String, Boolean> worldTypes,
      Instant createdAt, Instant lastPlayedAt,
      int spawnX, int spawnY, double groundLevel, double rockLevel,
      double time, boolean dayTime, int moonPhase, boolean bloodMoon, boolean eclipse,
      int dungeonX, int dungeonY, boolean crimson,
      // Downed*/Saved*/Unlocked*/Bought* and other one-way world flags, keyed by TEdit names.
      Map<String, Boolean> flags,
      int shadowOrbCount, int altarCount, boolean hardMode,
      int invasionType, int invasionSize, int invasionSizeStart,
      boolean raining, boolean sandstorm, boolean slimeRain, boolean lanternNight,
      boolean partyActive, List<Integer> partyingNpcs,
      // Ore choices made at world creation or on hardmode altars (tile ids, -1 when unset).
      Map<String, Integer> oreTiers,
      List<String> anglersFinishedToday, int anglerQuest,
      List<Integer> bannerKills, int claimableBanners) { }

  public record TileStats(
      long activeTiles, long wallTiles,
      // Tiles per type id (index = tile id).
      long[] countsByType,
      long waterTiles, long lavaTiles, long honeyTiles, long shimmerTiles,
      long redWires, long blueWires, long greenWires, long yellowWires, long actuators,
      long paintedTiles, long paintedWalls,
      // Weighted the same way the game computes its world alignment percentages.
      long alignmentGood, long alignmentEvil, long alignmentBlood, long alignmentSolid,
      // Multi-tile objects counted once at their top-left frame, keyed "tileId" or "tileId:style".
      Map<String, Integer> objectCounts) {

    public long count(int tileId) {
      return tileId >= 0 && tileId < countsByType.length ? countsByType[tileId] : 0;
    }

    public int percent(long part) {
      if (alignmentSolid <= 0) return 0;
      int value = (int) Math.round(part * 100.0 / alignmentSolid);
      return value == 0 && part > 0 ? 1 : value;
    }
  }

  public enum ContainerKind {
    CHEST, DRESSER, ITEM_FRAME, WEAPON_RACK, MANNEQUIN, HAT_RACK, FOOD_PLATTER, DISPLAY_JAR
  }

  public record Container(ContainerKind kind, int x, int y, Integer tileType, Integer tileStyle,
      String name, int slots, List<Item> items) { }

  public record Item(int slot, int itemId, int stack, int prefixId) { }

  public record Sign(int x, int y, Integer tileType, String text) { }

  public record TownNpc(int netId, String name, float x, float y, boolean homeless,
      int homeX, int homeY, Integer variation) { }

  public record Pylon(int x, int y, Integer style) { }

  public record Room(int npcId, int x, int y) { }
}
