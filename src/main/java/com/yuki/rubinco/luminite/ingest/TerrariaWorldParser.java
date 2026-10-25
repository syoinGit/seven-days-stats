package com.yuki.rubinco.luminite.ingest;

import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Container;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.ContainerKind;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Header;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Item;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Pylon;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Room;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Sign;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.TileStats;
import com.yuki.rubinco.luminite.ingest.TerrariaWorld.TownNpc;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only reader for the desktop world format (.wld). Layout follows TEdit's World.FileV2.cs
 * (MS-PL) and the game's own save order. Every section must end exactly where the file's section
 * table says it does, so a misread field fails the whole parse instead of producing wrong numbers.
 */
public class TerrariaWorldParser {
  /**
   * Part of a snapshot's identity (with the file hash), so a new value re-imports an unchanged save.
   * The managed-storage rule is included: totals counted under an older rule must be redone.
   */
  public static final String PARSER_VERSION = "tr-wld326-1-s" + TerrariaStorage.RULE_VERSION;
  /** The only save format verified against a real world. Others are rejected, not guessed. */
  public static final int SUPPORTED_VERSION = 326;
  private static final int SECTION_COUNT = 11;
  private static final int MAX_CHESTS = 8000;
  private static final int MAX_CHEST_SLOTS = 200;
  private static final int MAX_SIGNS = 10000;
  private static final int MAX_ENTITIES = 100000;
  private static final int MAX_STRING_BYTES = 4096;

  /** Multi-tile objects worth counting, with their frame size in pixels (width, height). */
  private static final Map<Integer, int[]> OBJECT_FRAMES = Map.of(
      12, new int[] {36, 36},   // Life Crystal
      26, new int[] {54, 36},   // Demon / Crimson Altar (style 0 / 1)
      28, new int[] {36, 36},   // Pots
      31, new int[] {36, 36},   // Shadow Orb / Crimson Heart (style 0 / 1)
      231, new int[] {54, 54},  // Bee Larva
      236, new int[] {36, 36},  // Life Fruit
      237, new int[] {54, 36},  // Lihzahrd Altar
      238, new int[] {36, 36}); // Plantera's Bulb
  private static final Set<Integer> STYLED_OBJECTS = Set.of(26, 31);
  private static final int[] HALLOW = {109, 492, 117, 116, 164, 402, 403, 115, 110, 113};
  private static final int[] CORRUPT = {23, 661, 25, 112, 163, 398, 400, 636, 24, 32};
  private static final int[] CRIMSON = {199, 662, 203, 234, 200, 399, 401, 205, 201, 352};
  private static final int[] SOLID = {2, 477, 1, 60, 53, 161};

  private final long maxBytes;

  public TerrariaWorldParser(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  public TerrariaWorld parse(byte[] input) {
    if (input.length > maxBytes) throw new TerrariaFormatException("file exceeds size limit");
    Reader r = new Reader(input);
    int version = r.i32();
    if (version != SUPPORTED_VERSION) {
      throw new TerrariaFormatException("unsupported world version " + version
          + " (supported: " + SUPPORTED_VERSION + ")");
    }
    if (!"relogic".equals(new String(r.bytes(7), StandardCharsets.US_ASCII)) || r.u8() != 2) {
      throw new TerrariaFormatException("not a desktop world file");
    }
    long revision = r.u32();
    r.bytes(8); // flags (favorite)
    int sectionCount = r.i16();
    if (sectionCount != SECTION_COUNT) throw new TerrariaFormatException("unexpected section count");
    int[] pointers = new int[sectionCount];
    for (int i = 0; i < sectionCount; i++) {
      pointers[i] = r.i32();
      if (pointers[i] <= 0 || pointers[i] > input.length || (i > 0 && pointers[i] < pointers[i - 1])) {
        throw new TerrariaFormatException("invalid section table");
      }
    }
    boolean[] frameImportant = bitArray(r);
    r.expectAt(pointers[0], "file header");

    r.limit(pointers[1]);
    Header header = header(r);
    r.expectAt(pointers[1], "world header");

    // Sections after the tiles are read first so the tile pass knows which frames to keep.
    List<RawChest> chests = chests(r.seek(pointers[2], pointers[3]));
    r.expectAt(pointers[3], "chests");
    List<RawSign> signs = signs(r.seek(pointers[3], pointers[4]));
    r.expectAt(pointers[4], "signs");
    List<Integer> shimmered = new ArrayList<>();
    int[] savedMobs = new int[1];
    List<TownNpc> townNpcs = npcs(r.seek(pointers[4], pointers[5]), shimmered, savedMobs);
    r.expectAt(pointers[5], "NPCs");
    Entities entities = entities(r.seek(pointers[5], pointers[6]));
    r.expectAt(pointers[6], "tile entities");
    Reader plates = r.seek(pointers[6], pointers[7]);
    int pressurePlates = plates.count(plates.i32(), MAX_ENTITIES);
    plates.bytes(8L * pressurePlates);
    r.expectAt(pointers[7], "pressure plates");
    List<Room> rooms = rooms(r.seek(pointers[7], pointers[8]));
    r.expectAt(pointers[8], "town manager");
    Map<String, Integer> kills = new LinkedHashMap<>();
    Set<String> sighted = new LinkedHashSet<>();
    Set<String> chatted = new LinkedHashSet<>();
    bestiary(r.seek(pointers[8], pointers[9]), kills, sighted, chatted);
    r.expectAt(pointers[9], "bestiary");
    // Journey mode powers (pointers[9]..[10]) are not used; the footer below still bounds them.
    r.seek(pointers[10], input.length);
    if (!r.bool() || !header.title().equals(r.string()) || r.i32() != header.worldId()) {
      throw new TerrariaFormatException("footer does not match the world header");
    }
    r.expectAt(input.length, "footer");

    Map<Long, int[]> frames = new HashMap<>();
    chests.forEach(chest -> frames.put(key(chest.x, chest.y, header.tilesHigh()), null));
    signs.forEach(sign -> frames.put(key(sign.x, sign.y, header.tilesHigh()), null));
    entities.pylons.forEach(pylon -> frames.put(key(pylon[0], pylon[1], header.tilesHigh()), null));
    TileStats tiles = tiles(r.seek(pointers[1], pointers[2]), header, frameImportant, frames);
    r.expectAt(pointers[2], "tiles");

    List<Container> containers = new ArrayList<>();
    for (RawChest chest : chests) {
      int[] frame = frames.get(key(chest.x, chest.y, header.tilesHigh()));
      Integer tileType = frame == null ? null : frame[0];
      ContainerKind kind = tileType != null && tileType == 88 ? ContainerKind.DRESSER : ContainerKind.CHEST;
      Integer style = frame == null ? null : frame[1] / (kind == ContainerKind.DRESSER ? 54 : 36);
      containers.add(new Container(kind, chest.x, chest.y, tileType, style, chest.name,
          chest.slots, chest.items));
    }
    containers.addAll(entities.displays);
    List<Sign> signList = signs.stream().map(sign -> {
      int[] frame = frames.get(key(sign.x, sign.y, header.tilesHigh()));
      return new Sign(sign.x, sign.y, frame == null ? null : frame[0], sign.text);
    }).toList();
    List<Pylon> pylons = entities.pylons.stream().map(pylon -> {
      int[] frame = frames.get(key(pylon[0], pylon[1], header.tilesHigh()));
      return new Pylon(pylon[0], pylon[1], frame == null || frame[0] != 597 ? null : frame[1] / 54);
    }).toList();

    return new TerrariaWorld(sha256(input), version, revision, header, tiles,
        List.copyOf(containers), signList, List.copyOf(townNpcs), List.copyOf(shimmered),
        savedMobs[0], pylons, entities.dummies, entities.sensors, pressurePlates, List.copyOf(rooms),
        Map.copyOf(kills), Set.copyOf(sighted), Set.copyOf(chatted));
  }

  private static Header header(Reader r) {
    String title = r.string();
    String seed = r.string();
    long genVersion = r.i64();
    String guid = guid(r.bytes(16));
    int worldId = r.i32();
    r.bytes(16); // world bounds in pixels
    int high = r.i32();
    int wide = r.i32();
    if (wide <= 0 || high <= 0 || wide > 20000 || high > 10000) {
      throw new TerrariaFormatException("invalid world size");
    }
    int gameMode = r.i32();
    if (gameMode < 0 || gameMode > 3) throw new TerrariaFormatException("invalid game mode");
    Map<String, Boolean> types = new LinkedHashMap<>();
    for (String name : List.of("DrunkWorld", "GoodWorld", "TenthAnniversaryWorld", "DontStarveWorld",
        "NotTheBeesWorld", "RemixWorld", "NoTrapsWorld", "ZenithWorld", "SkyblockWorld")) {
      types.put(name, r.bool());
    }
    Instant created = dateTime(r.i64());
    Instant lastPlayed = dateTime(r.i64());
    r.u8(); // moon type
    r.bytes(4L * 17); // tree, cave and background style boundaries
    int spawnX = r.i32();
    int spawnY = r.i32();
    double ground = r.f64();
    double rock = r.f64();
    double time = r.f64();
    boolean day = r.bool();
    int moonPhase = r.i32();
    boolean bloodMoon = r.bool();
    boolean eclipse = r.bool();
    int dungeonX = r.i32();
    int dungeonY = r.i32();
    boolean crimson = r.bool();

    Map<String, Boolean> flags = new LinkedHashMap<>();
    r.flags(flags, "DownedBoss1EyeofCthulhu", "DownedBoss2EaterofWorlds", "DownedBoss3Skeletron",
        "DownedQueenBee", "DownedMechBoss1TheDestroyer", "DownedMechBoss2TheTwins",
        "DownedMechBoss3SkeletronPrime", "DownedMechBossAny", "DownedPlantBoss", "DownedGolemBoss",
        "DownedSlimeKingBoss", "SavedGoblin", "SavedWizard", "SavedMech", "DownedGoblins",
        "DownedClown", "DownedFrost", "DownedPirates", "ShadowOrbSmashed");
    r.bool(); // spawn meteor
    int orbs = r.u8();
    int altars = r.i32();
    boolean hardMode = r.bool();
    flags.put("PartyOfDoom", r.bool());
    r.i32(); // invasion delay
    int invasionSize = r.i32();
    int invasionType = r.i32();
    r.f64(); // invasion x
    double slimeRainTime = r.f64();
    r.u8(); // sundial cooldown
    boolean raining = r.bool();
    r.i32(); // rain time
    r.f32(); // max rain
    Map<String, Integer> ores = new LinkedHashMap<>();
    ores.put("cobalt", r.i32());
    ores.put("mythril", r.i32());
    ores.put("adamantite", r.i32());
    r.bytes(8); // backgrounds
    r.i32(); // cloud background
    r.i16(); // clouds
    r.f32(); // wind
    List<String> anglers = new ArrayList<>();
    for (int i = r.count(r.i32(), 1000); i > 0; i--) anglers.add(r.string());
    r.flags(flags, "SavedAngler");
    int anglerQuest = r.i32();
    r.flags(flags, "SavedStylist", "SavedTaxCollector", "SavedGolfer");
    int invasionSizeStart = r.i32();
    r.i32(); // cultist delay
    List<Integer> bannerKills = new ArrayList<>();
    for (int i = r.count(r.i16(), 10000); i > 0; i--) bannerKills.add(r.i32());
    int claimable = r.count(r.i16(), 10000);
    r.bytes(2L * claimable);
    r.bool(); // fast forward time
    r.flags(flags, "DownedFishron", "DownedMartians", "DownedLunaticCultist", "DownedMoonlord",
        "DownedHalloweenKing", "DownedHalloweenTree", "DownedChristmasQueen", "DownedSanta",
        "DownedChristmasTree", "DownedCelestialSolar", "DownedCelestialVortex",
        "DownedCelestialNebula", "DownedCelestialStardust");
    r.bytes(4); // celestial pillars currently active
    flags.put("Apocalypse", r.bool());
    boolean partyManual = r.bool();
    boolean partyGenuine = r.bool();
    r.i32(); // party cooldown
    List<Integer> partying = new ArrayList<>();
    for (int i = r.count(r.i32(), 1000); i > 0; i--) partying.add(r.i32());
    boolean sandstorm = r.bool();
    r.bytes(12); // sandstorm time and severity
    r.flags(flags, "SavedBartender", "DownedDD2InvasionT1", "DownedDD2InvasionT2", "DownedDD2InvasionT3");
    r.bytes(5); // mushroom, underworld and extra tree backgrounds
    r.flags(flags, "CombatBookUsed");
    r.i32(); // lantern night cooldown
    boolean lanternGenuine = r.bool();
    boolean lanternManual = r.bool();
    r.bool(); // next night is genuine
    int treeTops = r.count(r.i32(), 1000);
    r.bytes(4L * treeTops);
    r.bytes(2); // forced Halloween / Christmas today
    ores.put("copper", r.i32());
    ores.put("iron", r.i32());
    ores.put("silver", r.i32());
    ores.put("gold", r.i32());
    r.flags(flags, "BoughtCat", "BoughtDog", "BoughtBunny", "DownedEmpressOfLight",
        "DownedQueenSlime", "DownedDeerclops", "UnlockedSlimeBlueSpawn", "UnlockedMerchantSpawn",
        "UnlockedDemolitionistSpawn", "UnlockedPartyGirlSpawn", "UnlockedDyeTraderSpawn",
        "UnlockedTruffleSpawn", "UnlockedArmsDealerSpawn", "UnlockedNurseSpawn",
        "UnlockedPrincessSpawn", "CombatBookVolumeTwoWasUsed", "PeddlersSatchelWasUsed",
        "UnlockedSlimeGreenSpawn", "UnlockedSlimeOldSpawn", "UnlockedSlimePurpleSpawn",
        "UnlockedSlimeRainbowSpawn", "UnlockedSlimeRedSpawn", "UnlockedSlimeYellowSpawn",
        "UnlockedSlimeCopperSpawn");
    r.bool(); // fast forward to dusk
    r.u8(); // moondial cooldown
    r.bytes(2); // forced Halloween / Christmas forever
    types.put("VampireSeed", r.bool());
    types.put("InfectedSeed", r.bool());
    r.i32(); // meteor showers
    r.i32(); // coin rain
    types.put("TeamBasedSpawnsSeed", r.bool());
    int teamSpawns = r.u8();
    r.bytes(4L * teamSpawns);
    types.put("DualDungeonsSeed", r.bool());
    types.put("MoreLightningSeed", r.bool());
    types.put("NoLightningSeed", r.bool());
    r.string(1 << 20); // world manifest (JSON)

    return new Header(title, seed, genVersion, guid, worldId, wide, high, gameMode, types, created,
        lastPlayed, spawnX, spawnY, ground, rock, time, day, moonPhase, bloodMoon, eclipse,
        dungeonX, dungeonY, crimson, flags, orbs, altars, hardMode, invasionType, invasionSize,
        invasionSizeStart, raining, sandstorm, slimeRainTime > 0, lanternGenuine || lanternManual,
        partyManual || partyGenuine, List.copyOf(partying), ores, List.copyOf(anglers), anglerQuest,
        List.copyOf(bannerKills), claimable);
  }

  private record RawChest(int x, int y, String name, int slots, List<Item> items) { }

  private record RawSign(int x, int y, String text) { }

  private static List<RawChest> chests(Reader r) {
    int count = r.count(r.i16(), MAX_CHESTS);
    List<RawChest> chests = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int x = r.i32();
      int y = r.i32();
      String name = r.string();
      int slots = r.count(r.i32(), MAX_CHEST_SLOTS);
      List<Item> items = new ArrayList<>();
      for (int slot = 0; slot < slots; slot++) {
        int stack = r.i16();
        if (stack > 0) items.add(new Item(slot, r.i32(), stack, r.u8()));
      }
      chests.add(new RawChest(x, y, name, slots, List.copyOf(items)));
    }
    return chests;
  }

  private static List<RawSign> signs(Reader r) {
    int count = r.count(r.i16(), MAX_SIGNS);
    List<RawSign> signs = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String text = r.string();
      signs.add(new RawSign(r.i32(), r.i32(), text));
    }
    return signs;
  }

  private static List<TownNpc> npcs(Reader r, List<Integer> shimmered, int[] savedMobs) {
    for (int i = r.count(r.i32(), 10000); i > 0; i--) shimmered.add(r.i32());
    List<TownNpc> npcs = new ArrayList<>();
    while (r.bool()) {
      if (npcs.size() >= 1000) throw new TerrariaFormatException("too many town NPCs");
      int netId = r.i32();
      String name = r.string();
      float x = r.f32();
      float y = r.f32();
      boolean homeless = r.bool();
      int homeX = r.i32();
      int homeY = r.i32();
      Integer variation = (r.u8() & 1) != 0 ? r.i32() : null;
      r.bool(); // homeless despawn
      npcs.add(new TownNpc(netId, name, x, y, homeless, homeX, homeY, variation));
    }
    while (r.bool()) {
      if (++savedMobs[0] > 10000) throw new TerrariaFormatException("too many saved mobs");
      r.i32();
      r.bytes(8);
    }
    return npcs;
  }

  private static final class Entities {
    final List<Container> displays = new ArrayList<>();
    final List<int[]> pylons = new ArrayList<>();
    int dummies;
    int sensors;
  }

  private static Entities entities(Reader r) {
    Entities result = new Entities();
    int count = r.count(r.i32(), MAX_ENTITIES);
    for (int i = 0; i < count; i++) {
      int type = r.u8();
      r.i32(); // entity id
      int x = r.i16();
      int y = r.i16();
      switch (type) {
        case 0 -> {
          r.i16();
          result.dummies++;
        }
        case 1 -> result.displays.add(single(ContainerKind.ITEM_FRAME, x, y, r));
        case 2 -> {
          r.u8();
          r.bool();
          result.sensors++;
        }
        case 3 -> result.displays.add(mannequin(x, y, r));
        case 4 -> result.displays.add(single(ContainerKind.WEAPON_RACK, x, y, r));
        case 5 -> {
          int bits = r.u8();
          List<Item> items = new ArrayList<>();
          for (int slot = 0; slot < 4; slot++) {
            if ((bits & (1 << slot)) != 0) displayItem(items, slot, r);
          }
          result.displays.add(new Container(ContainerKind.HAT_RACK, x, y, 475, null, null, 4,
              List.copyOf(items)));
        }
        case 6 -> result.displays.add(single(ContainerKind.FOOD_PLATTER, x, y, r));
        case 7 -> result.pylons.add(new int[] {x, y});
        case 8 -> result.displays.add(single(ContainerKind.DISPLAY_JAR, x, y, r));
        case 9, 10 -> { } // kite and critter anchors carry no data
        default -> throw new TerrariaFormatException("unknown tile entity type " + type);
      }
    }
    return result;
  }

  private static Container single(ContainerKind kind, int x, int y, Reader r) {
    List<Item> items = new ArrayList<>();
    displayItem(items, 0, r);
    Integer tile = switch (kind) {
      case ITEM_FRAME -> 395;
      case WEAPON_RACK -> 471;
      case FOOD_PLATTER -> 520;
      case DISPLAY_JAR -> 698;
      default -> null;
    };
    return new Container(kind, x, y, tile, null, null, 1, List.copyOf(items));
  }

  /** Equipment (9), dyes (9) and one misc slot; bit 8 of each mask lives in the third flag byte. */
  private static Container mannequin(int x, int y, Reader r) {
    int equipBits = r.u8();
    int dyeBits = r.u8();
    r.u8(); // pose
    int extra = r.u8();
    equipBits |= (extra & 2) != 0 ? 256 : 0;
    dyeBits |= (extra & 4) != 0 ? 256 : 0;
    List<Item> items = new ArrayList<>();
    for (int slot = 0; slot < 9; slot++) {
      if ((equipBits & (1 << slot)) != 0) displayItem(items, slot, r);
    }
    for (int slot = 0; slot < 9; slot++) {
      if ((dyeBits & (1 << slot)) != 0) displayItem(items, 9 + slot, r);
    }
    if ((extra & 1) != 0) displayItem(items, 18, r);
    return new Container(ContainerKind.MANNEQUIN, x, y, 470, null, null, 19, List.copyOf(items));
  }

  private static void displayItem(List<Item> items, int slot, Reader r) {
    int id = r.i16();
    int prefix = r.u8();
    int stack = r.i16();
    if (id != 0 && stack > 0) items.add(new Item(slot, id, stack, prefix));
  }

  private static List<Room> rooms(Reader r) {
    int count = r.count(r.i32(), 10000);
    List<Room> rooms = new ArrayList<>(count);
    for (int i = 0; i < count; i++) rooms.add(new Room(r.i32(), r.i32(), r.i32()));
    return rooms;
  }

  private static void bestiary(Reader r, Map<String, Integer> kills, Set<String> sighted,
      Set<String> chatted) {
    for (int i = r.count(r.i32(), MAX_ENTITIES); i > 0; i--) {
      String key = r.string();
      int value = r.i32();
      if (value < 0) throw new TerrariaFormatException("negative kill count");
      kills.put(key, value);
    }
    for (int i = r.count(r.i32(), MAX_ENTITIES); i > 0; i--) sighted.add(r.string());
    for (int i = r.count(r.i32(), MAX_ENTITIES); i > 0; i--) chatted.add(r.string());
  }

  /** One pass over every tile column; frames are kept only for the requested positions. */
  private static TileStats tiles(Reader r, Header header, boolean[] frameImportant,
      Map<Long, int[]> frames) {
    int wide = header.tilesWide();
    int high = header.tilesHigh();
    // Same bands as WorldGen.CountTiles: surface rows weigh 5, deeper rows 1, edges ignored.
    int surfaceEnd = (int) (header.groundLevel() + 1.0);
    long[] counts = new long[1 << 16];
    long[] weighted = new long[1 << 16];
    long active = 0, walls = 0, water = 0, lava = 0, honey = 0, shimmer = 0;
    long red = 0, blue = 0, green = 0, yellow = 0, actuators = 0, painted = 0, paintedWalls = 0;
    Map<String, Integer> objects = new TreeMap<>();
    Map<Integer, int[]> wanted = new HashMap<>();
    frames.keySet().stream().collect(java.util.stream.Collectors.groupingBy(key -> (int) (key / high)))
        .forEach((column, keys) -> wanted.put(column,
            keys.stream().mapToInt(key -> (int) (key % high)).toArray()));
    byte[] b = r.data;
    int p = r.position;
    int end = r.limit;
    try {
      for (int x = 0; x < wide; x++) {
        int y = 0;
        while (y < high) {
          int h1 = b[p++] & 0xFF;
          int h2 = 0, h3 = 0, h4 = 0;
          if ((h1 & 1) != 0) {
            h2 = b[p++] & 0xFF;
            if ((h2 & 1) != 0) {
              h3 = b[p++] & 0xFF;
              if ((h3 & 1) != 0) h4 = b[p++] & 0xFF;
            }
          }
          int type = -1, u = 0, v = 0;
          if ((h1 & 2) != 0) {
            if ((h1 & 0x20) == 0) {
              type = b[p++] & 0xFF;
            } else {
              type = (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
              p += 2;
            }
            if (type >= frameImportant.length || frameImportant[type]) {
              u = (short) ((b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8);
              v = (short) ((b[p + 2] & 0xFF) | (b[p + 3] & 0xFF) << 8);
              p += 4;
            }
            if ((h3 & 8) != 0) {
              p++;
              painted++;
            }
          }
          boolean wall = (h1 & 4) != 0;
          if (wall) {
            p++;
            if ((h3 & 16) != 0) {
              p++;
              paintedWalls++;
            }
          }
          int liquid = (h1 & 0x18) >> 3;
          if (liquid != 0) p++;
          if (h3 > 1 && (h3 & 0x40) != 0) p++; // high byte of the wall id
          int rleKind = (h1 & 0xC0) >> 6;
          int repeat = 0;
          if (rleKind == 1) {
            repeat = b[p++] & 0xFF;
          } else if (rleKind > 1) {
            repeat = (short) ((b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8);
            p += 2;
          }
          if (p > end) throw new TerrariaFormatException("tile data overruns its section");
          int n = repeat + 1;
          if (repeat < 0 || y + n > high) throw new TerrariaFormatException("tile run crosses a column");

          if (type >= 0) {
            active += n;
            counts[type] += n;
            int surface = Math.max(0, Math.min(y + n, surfaceEnd) - Math.max(y, 40));
            int deep = Math.max(0, Math.min(y + n, high - 40) - Math.max(y, surfaceEnd));
            weighted[type] += 5L * surface + deep;
            int[] frame = OBJECT_FRAMES.get(type);
            if (frame != null && u >= 0 && v >= 0 && u % frame[0] == 0 && v % frame[1] == 0) {
              String objectKey = STYLED_OBJECTS.contains(type) ? type + ":" + (u / frame[0]) : String.valueOf(type);
              objects.merge(objectKey, n, Integer::sum);
            }
          }
          if (wall) walls += n;
          if (liquid != 0) {
            if ((h3 & 0x80) != 0) shimmer += n;
            else if (liquid == 1) water += n;
            else if (liquid == 2) lava += n;
            else honey += n;
          }
          if ((h2 & 2) != 0) red += n;
          if ((h2 & 4) != 0) blue += n;
          if ((h2 & 8) != 0) green += n;
          if ((h3 & 0x20) != 0) yellow += n;
          if ((h3 & 2) != 0) actuators += n;
          int[] wantedRows = wanted.get(x);
          if (wantedRows != null) {
            for (int row : wantedRows) {
              if (row >= y && row < y + n) frames.put(key(x, row, high), new int[] {type, u, v});
            }
          }
          y += n;
        }
      }
    } catch (ArrayIndexOutOfBoundsException exception) {
      throw new TerrariaFormatException("tile data is truncated");
    }
    if (p > end) throw new TerrariaFormatException("tile data overruns its section");
    r.position = p;

    long good = sum(weighted, HALLOW), evil = sum(weighted, CORRUPT), blood = sum(weighted, CRIMSON);
    if (header.worldTypes().getOrDefault("RemixWorld", false)) {
      evil += weighted[474];
      blood += weighted[195];
    }
    long solid = sum(weighted, SOLID) + good + evil + blood;
    return new TileStats(active, walls, counts, water, lava, honey, shimmer, red, blue, green,
        yellow, actuators, painted, paintedWalls, good, evil, blood, solid, Map.copyOf(objects));
  }

  private static long sum(long[] values, int[] ids) {
    long total = 0;
    for (int id : ids) total += values[id];
    return total;
  }

  private static boolean[] bitArray(Reader r) {
    int length = r.count(r.i16(), 1 << 15);
    byte[] data = r.bytes((length + 7) / 8);
    boolean[] bits = new boolean[length];
    for (int i = 0; i < length; i++) bits[i] = (data[i / 8] >> (i % 8) & 1) != 0;
    return bits;
  }

  private static long key(int x, int y, int high) {
    return (long) x * high + y;
  }

  /** .NET DateTime.ToBinary(): 62-bit ticks; local values are stored as UTC ticks. */
  static Instant dateTime(long binary) {
    long ticks = binary & 0x3FFFFFFFFFFFFFFFL;
    if ((binary & 0x8000000000000000L) != 0 && ticks > 0x3FFFFFFFFFFFFFFFL - 864000000000L * 2) {
      ticks -= 0x4000000000000000L; // local value wrapped below zero
    }
    long epochTicks = ticks - 621355968000000000L;
    if (epochTicks <= 0) return null;
    Instant instant = Instant.ofEpochSecond(epochTicks / 10_000_000L, (epochTicks % 10_000_000L) * 100);
    // Outside the game's lifetime means the value is not a real timestamp.
    return instant.isBefore(Instant.parse("2011-01-01T00:00:00Z"))
        || instant.isAfter(Instant.parse("2100-01-01T00:00:00Z")) ? null : instant;
  }

  private static String guid(byte[] b) {
    // System.Guid byte order: first three groups little-endian.
    return String.format("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
        b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9], b[10], b[11], b[12], b[13],
        b[14], b[15]);
  }

  private static String sha256(byte[] input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static final class Reader {
    final byte[] data;
    int position;
    int limit;

    Reader(byte[] data) {
      this.data = data;
      this.limit = data.length;
    }

    Reader seek(int start, int end) {
      position = start;
      limit = end;
      return this;
    }

    void limit(int end) {
      limit = end;
    }

    void expectAt(int expected, String section) {
      if (position != expected) {
        throw new TerrariaFormatException(section + " section ends at " + position + ", expected " + expected);
      }
    }

    void need(long n) {
      if (n < 0 || position + n > limit) throw new TerrariaFormatException("truncated data at " + position);
    }

    int u8() {
      need(1);
      return data[position++] & 0xFF;
    }

    int i16() {
      need(2);
      int value = (short) ((data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8);
      position += 2;
      return value;
    }

    int i32() {
      need(4);
      int value = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8
          | (data[position + 2] & 0xFF) << 16 | (data[position + 3] & 0xFF) << 24;
      position += 4;
      return value;
    }

    long u32() {
      return i32() & 0xFFFFFFFFL;
    }

    long i64() {
      long low = u32();
      return low | ((long) i32() << 32);
    }

    float f32() {
      return Float.intBitsToFloat(i32());
    }

    double f64() {
      return Double.longBitsToDouble(i64());
    }

    boolean bool() {
      int value = u8();
      if (value > 1) throw new TerrariaFormatException("invalid boolean at " + (position - 1));
      return value == 1;
    }

    byte[] bytes(long n) {
      need(n);
      byte[] copy = java.util.Arrays.copyOfRange(data, position, position + (int) n);
      position += (int) n;
      return copy;
    }

    int count(int value, int max) {
      if (value < 0 || value > max) throw new TerrariaFormatException("invalid count " + value);
      return value;
    }

    /** BinaryWriter string: 7-bit encoded byte length, then UTF-8. */
    String string() {
      return string(MAX_STRING_BYTES);
    }

    String string(int maxBytes) {
      int length = 0;
      for (int shift = 0; ; shift += 7) {
        if (shift > 28) throw new TerrariaFormatException("invalid string length");
        int b = u8();
        length |= (b & 0x7F) << shift;
        if (b < 0x80) break;
      }
      if (length > maxBytes) throw new TerrariaFormatException("string too long");
      try {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes(length))).toString();
      } catch (CharacterCodingException exception) {
        throw new TerrariaFormatException("invalid UTF-8 string");
      }
    }

    void flags(Map<String, Boolean> target, String... names) {
      for (String name : names) target.put(name, bool());
    }
  }
}
