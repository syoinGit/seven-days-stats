package com.yuki.rubinco.luminite.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.yuki.rubinco.luminite.ingest.TerrariaWorld.ContainerKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TerrariaWorldParserTests {
  /** A real world kept out of the public repository (gitignored). Tests skip when it is absent. */
  static final Path REAL_WORLD = Path.of("terraria/Rubicon.wld");
  private final TerrariaWorldParser parser = new TerrariaWorldParser(64L * 1024 * 1024);

  @Test
  void readsEverySectionOfTheRealWorldToItsRecordedBoundary() throws Exception {
    assumeTrue(Files.exists(REAL_WORLD), "real world file not present");
    TerrariaWorld world = parser.parse(Files.readAllBytes(REAL_WORLD));

    assertThat(world.version()).isEqualTo(326);
    assertThat(world.sourceSha256()).isEqualTo("82ee8e7573581c01e3de304b23f66ee78bfe3e84c5df2abc1bb577195a7776b5");
    var header = world.header();
    assertThat(header.title()).isEqualTo("Rubicon");
    assertThat(header.seed()).isEqualTo("124605231");
    assertThat(header.worldGuid()).isEqualTo("a62fc45e-d07d-4359-b64c-69c5f3b299c3");
    assertThat(header.tilesWide()).isEqualTo(6400);
    assertThat(header.tilesHigh()).isEqualTo(1800);
    assertThat(header.gameMode()).isEqualTo(1);
    assertThat(header.hardMode()).isTrue();
    assertThat(header.crimson()).isFalse();
    // Matches the independently written prototype extractor for the same file.
    assertThat(header.flags()).containsEntry("DownedMoonlord", true).containsEntry("DownedFrost", false)
        .containsEntry("SavedTaxCollector", false).containsEntry("UnlockedTruffleSpawn", false)
        .containsEntry("DownedDeerclops", true).containsEntry("UnlockedSlimeCopperSpawn", true);
    assertThat(header.createdAt()).isNotNull();

    assertThat(world.containers().stream().filter(c -> c.kind() == ContainerKind.CHEST
        || c.kind() == ContainerKind.DRESSER)).hasSize(355);
    assertThat(world.containers().stream().filter(c -> c.kind() == ContainerKind.ITEM_FRAME)).hasSize(44);
    assertThat(world.containers().stream().filter(c -> c.kind() == ContainerKind.CHEST))
        .allSatisfy(chest -> assertThat(chest.tileType()).isIn(21, 467));
    assertThat(world.signs()).hasSize(84).allSatisfy(sign -> assertThat(sign.tileType()).isNotNull());
    assertThat(world.townNpcs()).hasSize(27);
    assertThat(world.pylons()).hasSize(6).allSatisfy(pylon -> assertThat(pylon.style()).isNotNull());
    assertThat(world.rooms()).hasSize(24);
    assertThat(world.bestiaryKills()).hasSize(467).containsEntry("BlueSlime", 1502);
    assertThat(world.bestiarySighted()).hasSize(73);
    assertThat(world.bestiaryChatted()).hasSize(38);
    assertThat(world.tiles().activeTiles()).isEqualTo(Arrays.stream(world.tiles().countsByType()).sum());
    assertThat(world.tiles().alignmentSolid()).isPositive();
  }

  @Test
  void rejectsOtherVersionsTruncationAndTampering() {
    byte[] bytes = sample().build();

    byte[] otherVersion = bytes.clone();
    otherVersion[0] = 0x47;
    assertThatThrownBy(() -> parser.parse(otherVersion)).isInstanceOf(TerrariaFormatException.class)
        .hasMessageContaining("unsupported world version");

    assertThatThrownBy(() -> parser.parse(Arrays.copyOf(bytes, bytes.length - 3)))
        .isInstanceOf(TerrariaFormatException.class);

    byte[] footer = bytes.clone();
    footer[footer.length - 1] ^= 0x01;
    assertThatThrownBy(() -> parser.parse(footer)).isInstanceOf(TerrariaFormatException.class)
        .hasMessageContaining("footer");
  }

  @Test
  void readsASyntheticWorld() {
    TerrariaWorld world = parser.parse(sample().build());

    assertThat(world.header().title()).isEqualTo("テスト世界");
    assertThat(world.header().hardMode()).isTrue();
    assertThat(world.header().flags()).containsEntry("DownedBoss1EyeofCthulhu", true)
        .containsEntry("DownedMoonlord", false);
    assertThat(world.containers()).hasSize(2);
    var chest = world.containers().get(0);
    assertThat(chest.kind()).isEqualTo(ContainerKind.CHEST);
    assertThat(chest.tileType()).isEqualTo(21);
    assertThat(chest.tileStyle()).isZero();
    assertThat(chest.name()).isEqualTo("倉庫");
    assertThat(chest.items()).extracting(TerrariaWorld.Item::itemId).containsExactly(22, 29);
    assertThat(world.containers().get(1).kind()).isEqualTo(ContainerKind.ITEM_FRAME);
    assertThat(world.signs()).singleElement().satisfies(sign -> assertThat(sign.tileType()).isEqualTo(85));
    assertThat(world.townNpcs()).singleElement().satisfies(npc -> assertThat(npc.name()).isEqualTo("アンドリュー"));
    assertThat(world.bestiaryKills()).containsEntry("BlueSlime", 12);
    assertThat(world.tiles().count(21)).isEqualTo(1);
  }

  static SyntheticTerrariaWorld sample() {
    SyntheticTerrariaWorld world = new SyntheticTerrariaWorld();
    world.hardMode = true;
    world.flags.add("DownedBoss1EyeofCthulhu");
    world.chest(4, 12, "倉庫", new SyntheticTerrariaWorld.Slot(0, 22, 30, 0),
        new SyntheticTerrariaWorld.Slot(5, 29, 2, 0));
    world.itemFrames.add(new int[] {8, 9, 29, 1});
    world.tombstone(20, 9, "ゆうしゃ was slain.\nOctober 1, 2026");
    world.npcs.add(new SyntheticTerrariaWorld.Npc(22, "アンドリュー", false, 10, 9));
    world.kills.put("BlueSlime", 12);
    world.sighted.add("BlueSlime");
    return world;
  }

  @Test
  void convertsDotNetBinaryDateTimes() {
    // 2026-10-24T05:00:00Z stored as a local (kind=2) value: UTC ticks with the top bit set.
    long ticks = 621355968000000000L + java.time.Instant.parse("2026-10-24T05:00:00Z").getEpochSecond() * 10_000_000L;
    assertThat(TerrariaWorldParser.dateTime(ticks | 0x8000000000000000L))
        .isEqualTo(java.time.Instant.parse("2026-10-24T05:00:00Z"));
    assertThat(TerrariaWorldParser.dateTime(0)).isNull();
  }
}
