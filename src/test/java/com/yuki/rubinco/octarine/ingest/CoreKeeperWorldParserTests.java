package com.yuki.rubinco.octarine.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fixture: the supplied new world (prototype output: wall lowered=false, no supported bosses). */
class CoreKeeperWorldParserTests {
  static final Path FIXTURE = Path.of("src/test/resources/corekeeper/new-world.world.gzip");
  private final CoreKeeperWorldParser parser =
      new CoreKeeperWorldParser(64L << 20, 256L << 20, new ObjectMapper());

  @Test
  void reproducesThePrototypeResultForTheSuppliedNewWorld() throws Exception {
    byte[] input = Files.readAllBytes(FIXTURE);
    var world = parser.parse(input);

    assertThat(world.sourceSha256())
        .isEqualTo("b6abea46c5be70c7d6c55aefad8289b9cd2803d46452d3c09a33893f71997ecd");
    assertThat(world.containerVersion()).isEqualTo(77);
    assertThat(world.decompressedBytes()).isEqualTo(9823828);
    assertThat(world.greatWallLowered()).isFalse();
    assertThat(world.recordedObjects()).containsExactly(
        new CoreKeeperWorld.RecordedObject(300, 2, 0),
        new CoreKeeperWorld.RecordedObject(1610, 28, 0),
        new CoreKeeperWorld.RecordedObject(5500, 2, 0),
        new CoreKeeperWorld.RecordedObject(9800, 4, 0));
    assertThat(world.bossKilledFromGameLogic()).hasSize(10).doesNotContainValue(true);
    assertThat(world.globalSeed()).isEqualTo(2076732697L);
    assertThat(world.generationJson()).startsWith("{\"globalSeed\":2076732697");
    assertThat(Files.readAllBytes(FIXTURE)).isEqualTo(input);
  }

  @Test
  void rejectsTruncatedOrForeignInput() throws Exception {
    byte[] input = Files.readAllBytes(FIXTURE);
    assertThatThrownBy(() -> parser.parse(Arrays.copyOf(input, input.length / 2)))
        .isInstanceOf(CoreKeeperFormatException.class);
    assertThatThrownBy(() -> parser.parse("not a world".getBytes()))
        .isInstanceOf(CoreKeeperFormatException.class);
    byte[] container = parser.decompress(input);
    assertThatThrownBy(() -> parser.parseContainer(
        Arrays.copyOf(container, container.length - 100), input))
        .isInstanceOf(CoreKeeperFormatException.class);
  }

  @Test
  void rejectsUnknownContainerVersionsAndCorruptNodeTables() throws Exception {
    byte[] input = Files.readAllBytes(FIXTURE);
    byte[] container = parser.decompress(input);

    byte[] newer = container.clone();
    ByteBuffer.wrap(newer).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 78);
    assertThatThrownBy(() -> parser.parseContainer(newer, input))
        .hasMessage("Unsupported container version");

    byte[] badNodes = container.clone();
    long nodeStart = ByteBuffer.wrap(badNodes).order(ByteOrder.LITTLE_ENDIAN).getLong(104);
    ByteBuffer.wrap(badNodes).order(ByteOrder.LITTLE_ENDIAN).putInt((int) nodeStart + 24, 8);
    assertThatThrownBy(() -> parser.parseContainer(badNodes, input))
        .hasMessage("Invalid node");
  }

  @Test
  void enforcesCompressedAndDecompressedSizeLimits() throws Exception {
    byte[] input = Files.readAllBytes(FIXTURE);
    var small = new CoreKeeperWorldParser(1024, 256L << 20, new ObjectMapper());
    assertThatThrownBy(() -> small.parse(input)).hasMessageContaining("compressed size limit");
    var tight = new CoreKeeperWorldParser(64L << 20, 1L << 20, new ObjectMapper());
    assertThatThrownBy(() -> tight.parse(input)).hasMessageContaining("size limit");
  }
}
