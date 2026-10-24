package com.yuki.rubinco.luminite.ingest;

import java.util.List;

/**
 * Fixed game ids used for statistics. Ids come from the game's TileID/ItemID tables; display
 * names are resolved from the generated name tables, so no game text is stored here.
 */
public final class TerrariaCatalog {
  private TerrariaCatalog() { }

  /** A tile type counted for the statistics page, labelled with the item it drops. */
  public record Resource(String group, int tileId, int itemId) { }

  public static final List<Resource> RESOURCES = List.of(
      ore(7, 12), ore(166, 699), ore(6, 11), ore(167, 700), ore(9, 14), ore(168, 701),
      ore(8, 13), ore(169, 702), ore(22, 56), ore(204, 880), ore(37, 116), ore(56, 173),
      ore(58, 174), ore(107, 364), ore(221, 1104), ore(108, 365), ore(222, 1105), ore(111, 366),
      ore(223, 1106), ore(211, 947), ore(408, 3460),
      gem(67, 181), gem(66, 180), gem(63, 177), gem(65, 179), gem(64, 178), gem(68, 182),
      block(0, 2), block(1, 3), block(59, 176), block(53, 169), block(397, 3272),
      block(396, 3271), block(40, 133), block(123, 424), block(147, 593), block(161, 664),
      block(224, 1103), block(57, 172), block(25, 61), block(203, 836), block(117, 409),
      block(367, 3081), block(368, 3086), block(225, 1124), block(226, 1101), block(41, 134),
      block(43, 137), block(44, 139), block(189, 751), block(404, 3347), block(659, 5349));

  /** Multi-tile objects counted once each (keys as produced by the parser), named by map legend. */
  public record WorldObject(String key, String mapObjectKey, int itemId) { }

  public static final List<WorldObject> OBJECTS = List.of(
      new WorldObject("12", null, 29),
      new WorldObject("236", null, 1291),
      new WorldObject("31:0", null, 115),
      new WorldObject("31:1", null, 3062),
      new WorldObject("26:0", "DemonAltar", 5532),
      new WorldObject("26:1", "CrimsonAltar", 5533),
      new WorldObject("238", "PlanterasBulb", -1),
      new WorldObject("231", "Larva", -1),
      new WorldObject("237", null, 1292),
      new WorldObject("28", "Pot", -1));

  /** TeleportPylonType order; index is the pylon tile style (frameX / 54). */
  public static final List<Integer> PYLON_ITEMS = List.of(
      4876, 4875, 4916, 4917, 4918, 4919, 4920, 4921, 4951, 5652, 5653);

  /** Main.anglerQuestItemNetIDs. */
  public static int anglerQuestItem(int quest) {
    if (quest >= 0 && quest < 39) return 2450 + quest;
    if (quest == 39) return 4393;
    if (quest == 40) return 4394;
    return -1;
  }

  public static Integer pylonItem(Integer style) {
    return style != null && style >= 0 && style < PYLON_ITEMS.size() ? PYLON_ITEMS.get(style) : null;
  }

  private static Resource ore(int tile, int item) {
    return new Resource("ORE", tile, item);
  }

  private static Resource gem(int tile, int item) {
    return new Resource("GEM", tile, item);
  }

  private static Resource block(int tile, int item) {
    return new Resource("BLOCK", tile, item);
  }
}
