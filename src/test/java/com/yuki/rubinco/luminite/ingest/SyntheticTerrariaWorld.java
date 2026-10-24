package com.yuki.rubinco.luminite.ingest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a small v326 world in the same layout the parser reads, so tests do not depend on a
 * real world file (which stays out of the public repository).
 */
public final class SyntheticTerrariaWorld {
  public record Slot(int slot, int itemId, int stack, int prefix) { }

  public record Chest(int x, int y, String name, List<Slot> slots) { }

  public record Sign(int x, int y, String text) { }

  public record Tile(int x, int y, int type, int u, int v) { }

  public record Npc(int netId, String name, boolean homeless, int homeX, int homeY) { }

  public String title = "テスト世界";
  public int worldId = 4242;
  public int wide = 40;
  public int high = 30;
  public int gameMode = 1;
  public boolean hardMode;
  public boolean crimson = true;
  public double groundLevel = 10;
  public double rockLevel = 18;
  public final Set<String> flags = new LinkedHashSet<>();
  public final List<Chest> chests = new ArrayList<>();
  public final List<Sign> signs = new ArrayList<>();
  public final List<Tile> tiles = new ArrayList<>();
  public final List<Npc> npcs = new ArrayList<>();
  public final List<int[]> itemFrames = new ArrayList<>(); // x, y, item, stack
  public final Map<String, Integer> kills = new LinkedHashMap<>();
  public final Set<String> sighted = new LinkedHashSet<>();

  public SyntheticTerrariaWorld chest(int x, int y, String name, Slot... slots) {
    chests.add(new Chest(x, y, name, List.of(slots)));
    tiles.add(new Tile(x, y, 21, 0, 0));
    return this;
  }

  public SyntheticTerrariaWorld tombstone(int x, int y, String text) {
    signs.add(new Sign(x, y, text));
    tiles.add(new Tile(x, y, 85, 0, 0));
    return this;
  }

  public byte[] build() {
    Out header = new Out();
    header(header);
    Out tileData = new Out();
    tiles(tileData);
    Out chestData = new Out();
    chestData.i16(chests.size());
    for (Chest chest : chests) {
      chestData.i32(chest.x()).i32(chest.y()).str(chest.name()).i32(40);
      for (int slot = 0; slot < 40; slot++) {
        int index = slot;
        Slot item = chest.slots().stream().filter(s -> s.slot() == index).findFirst().orElse(null);
        if (item == null) {
          chestData.i16(0);
        } else {
          chestData.i16(item.stack()).i32(item.itemId()).u8(item.prefix());
        }
      }
    }
    Out signData = new Out();
    signData.i16(signs.size());
    signs.forEach(sign -> signData.str(sign.text()).i32(sign.x()).i32(sign.y()));
    Out npcData = new Out();
    npcData.i32(0);
    for (Npc npc : npcs) {
      npcData.bool(true).i32(npc.netId()).str(npc.name()).f32(npc.homeX() * 16).f32(npc.homeY() * 16)
          .bool(npc.homeless()).i32(npc.homeX()).i32(npc.homeY()).u8(0).bool(false);
    }
    npcData.bool(false).bool(false);
    Out entityData = new Out();
    entityData.i32(itemFrames.size());
    int id = 0;
    for (int[] frame : itemFrames) {
      entityData.u8(1).i32(id++).i16(frame[0]).i16(frame[1]).i16(frame[2]).u8(0).i16(frame[3]);
    }
    Out plates = new Out().i32(0);
    Out town = new Out().i32(0);
    Out bestiary = new Out();
    bestiary.i32(kills.size());
    kills.forEach((key, value) -> bestiary.str(key).i32(value));
    bestiary.i32(sighted.size());
    sighted.forEach(bestiary::str);
    bestiary.i32(0);
    Out creative = new Out();
    Out footer = new Out().bool(true).str(title).i32(worldId);

    List<byte[]> sections = List.of(header.bytes(), tileData.bytes(), chestData.bytes(), signData.bytes(),
        npcData.bytes(), entityData.bytes(), plates.bytes(), town.bytes(), bestiary.bytes(), creative.bytes());
    int frameBits = 700;
    int fileHeaderLength = 4 + 7 + 1 + 4 + 8 + 2 + 4 * 11 + 2 + (frameBits + 7) / 8;
    Out file = new Out();
    file.i32(326).raw("relogic".getBytes(StandardCharsets.US_ASCII)).u8(2).i32(1).raw(new byte[8]).i16(11);
    int position = fileHeaderLength;
    for (byte[] section : sections) {
      file.i32(position);
      position += section.length;
    }
    file.i32(position); // footer
    byte[] important = new byte[(frameBits + 7) / 8];
    for (int type : new int[] {21, 85, 88, 597}) important[type / 8] |= (byte) (1 << (type % 8));
    file.i16(frameBits).raw(important);
    sections.forEach(file::raw);
    file.raw(footer.bytes());
    return file.bytes();
  }

  private void header(Out o) {
    o.str(title).str("synthetic-seed").i64(0).raw(new byte[16]).i32(worldId).raw(new byte[16]);
    o.i32(high).i32(wide).i32(gameMode);
    for (int i = 0; i < 9; i++) o.bool(false);
    o.i64(0).i64(0).u8(0).raw(new byte[4 * 17]);
    o.i32(wide / 2).i32((int) groundLevel).f64(groundLevel).f64(rockLevel).f64(3600).bool(true).i32(0)
        .bool(false).bool(false).i32(5).i32(15).bool(crimson);
    flags(o, "DownedBoss1EyeofCthulhu", "DownedBoss2EaterofWorlds", "DownedBoss3Skeletron",
        "DownedQueenBee", "DownedMechBoss1TheDestroyer", "DownedMechBoss2TheTwins",
        "DownedMechBoss3SkeletronPrime", "DownedMechBossAny", "DownedPlantBoss", "DownedGolemBoss",
        "DownedSlimeKingBoss", "SavedGoblin", "SavedWizard", "SavedMech", "DownedGoblins",
        "DownedClown", "DownedFrost", "DownedPirates", "ShadowOrbSmashed");
    o.bool(false).u8(0).i32(0).bool(hardMode).bool(false).i32(0).i32(0).i32(0).f64(0).f64(0).u8(0)
        .bool(false).i32(0).f32(0).i32(107).i32(108).i32(111).raw(new byte[8]).i32(0).i16(0).f32(0);
    o.i32(0);
    flags(o, "SavedAngler");
    o.i32(0);
    flags(o, "SavedStylist", "SavedTaxCollector", "SavedGolfer");
    o.i32(0).i32(0).i16(0).i16(0).bool(false);
    flags(o, "DownedFishron", "DownedMartians", "DownedLunaticCultist", "DownedMoonlord",
        "DownedHalloweenKing", "DownedHalloweenTree", "DownedChristmasQueen", "DownedSanta",
        "DownedChristmasTree", "DownedCelestialSolar", "DownedCelestialVortex",
        "DownedCelestialNebula", "DownedCelestialStardust");
    o.raw(new byte[4]).bool(false).bool(false).bool(false).i32(0).i32(0).bool(false).raw(new byte[12]);
    flags(o, "SavedBartender", "DownedDD2InvasionT1", "DownedDD2InvasionT2", "DownedDD2InvasionT3");
    o.raw(new byte[5]);
    flags(o, "CombatBookUsed");
    o.i32(0).bool(false).bool(false).bool(false).i32(0).raw(new byte[2]).i32(7).i32(6).i32(9).i32(8);
    flags(o, "BoughtCat", "BoughtDog", "BoughtBunny", "DownedEmpressOfLight",
        "DownedQueenSlime", "DownedDeerclops", "UnlockedSlimeBlueSpawn", "UnlockedMerchantSpawn",
        "UnlockedDemolitionistSpawn", "UnlockedPartyGirlSpawn", "UnlockedDyeTraderSpawn",
        "UnlockedTruffleSpawn", "UnlockedArmsDealerSpawn", "UnlockedNurseSpawn",
        "UnlockedPrincessSpawn", "CombatBookVolumeTwoWasUsed", "PeddlersSatchelWasUsed",
        "UnlockedSlimeGreenSpawn", "UnlockedSlimeOldSpawn", "UnlockedSlimePurpleSpawn",
        "UnlockedSlimeRainbowSpawn", "UnlockedSlimeRedSpawn", "UnlockedSlimeYellowSpawn",
        "UnlockedSlimeCopperSpawn");
    o.bool(false).u8(0).raw(new byte[2]).bool(false).bool(false).i32(0).i32(0).bool(false).u8(0)
        .bool(false).bool(false).bool(false).str("{}");
  }

  private void flags(Out o, String... names) {
    for (String name : names) o.bool(flags.contains(name));
  }

  /** Empty columns as one run each, with single frame-important tiles where requested. */
  private void tiles(Out o) {
    for (int x = 0; x < wide; x++) {
      int y = 0;
      while (y < high) {
        int row = y;
        int column = x;
        Tile tile = tiles.stream().filter(t -> t.x() == column && t.y() == row).findFirst().orElse(null);
        if (tile != null) {
          if (tile.type() < 256) {
            o.u8(0x02).u8(tile.type());
          } else {
            o.u8(0x22).i16(tile.type());
          }
          o.i16(tile.u()).i16(tile.v());
          y++;
          continue;
        }
        int next = tiles.stream().filter(t -> t.x() == column && t.y() > row).mapToInt(Tile::y).min()
            .orElse(high);
        o.u8(0x80).i16(next - y - 1);
        y = next;
      }
    }
  }

  private static final class Out {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Out u8(int value) {
      out.write(value);
      return this;
    }

    Out bool(boolean value) {
      return u8(value ? 1 : 0);
    }

    Out i16(int value) {
      return u8(value & 0xFF).u8(value >> 8 & 0xFF);
    }

    Out i32(int value) {
      return i16(value & 0xFFFF).i16(value >>> 16);
    }

    Out i64(long value) {
      return i32((int) value).i32((int) (value >>> 32));
    }

    Out f32(float value) {
      return i32(Float.floatToIntBits(value));
    }

    Out f64(double value) {
      return i64(Double.doubleToLongBits(value));
    }

    Out raw(byte[] bytes) {
      out.writeBytes(bytes);
      return this;
    }

    Out str(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      int length = bytes.length;
      while (length >= 0x80) {
        u8(length & 0x7F | 0x80);
        length >>>= 7;
      }
      return u8(length).raw(bytes);
    }

    byte[] bytes() {
      return out.toByteArray();
    }
  }
}
