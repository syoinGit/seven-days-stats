package com.yuki.rubinco.octarine.ingest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.brotli.dec.BrotliInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only reader for the Core Keeper DOTSBIN v77 world layout.
 *
 * <p>Ported from the reviewed {@code extract-progress.cjs} prototype. Every structural assumption
 * is checked; an unexpected layout throws instead of returning "not achieved" progress.
 */
public final class CoreKeeperWorldParser {
  /** Changes whenever extraction rules change, so the same save is re-parsed after an upgrade. */
  public static final String PARSER_VERSION = "ck-dotsbin77-1";
  public static final int CONTAINER_VERSION = 77;

  private static final byte[] MAGIC = "DOTSBIN!".getBytes(StandardCharsets.US_ASCII);
  private static final int HEADER_SIZE = 152;
  private static final int CHUNK_SIZE = 16384;
  private static final int CHUNK_BUFFER = 16320;
  private static final String ARCHETYPE_NODE = "F5364E1CCB62466A9883F6F9554D4F0C";
  private static final String CHUNK_NODE = "2EA7CE3325F04D7A84CEAB46790C628A";
  private static final String BUFFER_NODE = "E33124BFAC2649D792DE36E4611DDD70";
  private static final long ENTITY_TYPE = Long.parseUnsignedLong("2308415037858323387");
  private static final long SINGLETON_TAG_TYPE = Long.parseUnsignedLong("8089487776991448321");
  private static final long KILLED_ENEMIES_TYPE = Long.parseUnsignedLong("3317929161124881020");
  private static final long GREAT_WALL_TYPE = Long.parseUnsignedLong("13321463714558854008");
  private static final int KILLED_ENEMIES_COMPONENT_SIZE = 136;
  private static final int KILLED_ENEMIES_INLINE_CAPACITY = 10;
  private static final int RECORD_SIZE = 12;
  private static final byte[] GENERATION_PREFIX =
      "{\"globalSeed\"".getBytes(StandardCharsets.US_ASCII);

  /** Boss ObjectIDs whose kill flags UpdateWorldInfoSystem derives from the same record list. */
  public static final Map<String, Integer> GAME_LOGIC_BOSS_IDS = orderedBosses();

  private final long maxCompressedBytes;
  private final long maxDecompressedBytes;
  private final ObjectMapper objectMapper;

  public CoreKeeperWorldParser(long maxCompressedBytes, long maxDecompressedBytes,
      ObjectMapper objectMapper) {
    this.maxCompressedBytes = maxCompressedBytes;
    this.maxDecompressedBytes = maxDecompressedBytes;
    this.objectMapper = objectMapper;
  }

  public CoreKeeperWorld parse(byte[] input) {
    if (input.length > maxCompressedBytes) {
      throw new CoreKeeperFormatException("Input exceeds the compressed size limit");
    }
    return parseContainer(decompress(input), input);
  }

  /** Visible for tests that need to feed altered containers without a Brotli encoder. */
  CoreKeeperWorld parseContainer(byte[] container, byte[] input) {
    Buffer b = new Buffer(container);
    check(b.length() >= HEADER_SIZE && b.startsWith(MAGIC), "Unsupported container");
    int version = b.i32(8);
    check(version == CONTAINER_VERSION && b.i32(12) == HEADER_SIZE, "Unsupported container version");

    long nodeStart = b.i64(104);
    long nodeEnd = nodeStart + b.i32(112);
    check(nodeStart >= HEADER_SIZE && nodeEnd == b.length(), "Invalid node bounds");
    Map<String, Node> nodes = new HashMap<>();
    for (long o = nodeStart; o < nodeEnd; ) {
      int size = b.i32(o + 24);
      check(size >= 72 && o + size <= nodeEnd, "Invalid node");
      nodes.put(nodeId(b, o + 8), new Node(b.i64(o + 56), b.i64(o + 64),
          size >= 80 ? b.i32(o + 72) : null));
      o += size;
    }

    Node archetypeNode = node(nodes, ARCHETYPE_NODE, nodeStart);
    long o = archetypeNode.start();
    int typeCount = b.i32(o);
    o += 4;
    check(typeCount > 0 && typeCount < 10000, "Invalid type count");
    long[] hashes = new long[typeCount];
    for (int j = 0; j < typeCount; j++, o += 8) hashes[j] = b.i64(o);
    int archetypeCount = b.i32(o);
    o += 4;
    check(archetypeCount > 0 && archetypeCount < 100000, "Invalid archetype count");
    List<Archetype> archetypes = new ArrayList<>();
    for (int j = 0; j < archetypeCount; j++) {
      int count = b.i32(o);
      int typesInArchetype = b.i32(o + 4);
      o += 8;
      check(count >= 0 && typesInArchetype >= 0 && typesInArchetype <= typeCount,
          "Invalid archetype");
      long[] types = new long[typesInArchetype];
      for (int k = 0; k < typesInArchetype; k++, o += 4) {
        int index = b.i32(o);
        check(index >= 0 && index < typeCount, "Unsupported type index");
        types[k] = hashes[index];
      }
      archetypes.add(new Archetype(count, types));
    }
    check(o == archetypeNode.start() + archetypeNode.size(), "Unexpected archetype trailing data");

    Node chunkNode = node(nodes, CHUNK_NODE, nodeStart);
    check(chunkNode.size() % CHUNK_SIZE == 0, "Invalid chunks");
    int chunkCount = Math.toIntExact(chunkNode.size() / CHUNK_SIZE);

    Node bufferNode = node(nodes, BUFFER_NODE, nodeStart);
    long bufferEnd = bufferNode.start() + bufferNode.size();
    o = bufferNode.start();
    check(b.i32(o) == chunkCount, "Buffer chunk mismatch");
    o += 4;
    Map<String, byte[]> patches = new HashMap<>();
    for (int j = 0; j < chunkCount; j++) {
      int patchCount = b.i32(o);
      o += 4;
      check(patchCount >= 0 && patchCount < 100000, "Invalid patches");
      for (int k = 0; k < patchCount; k++) {
        int offset = b.i32(o);
        int length = b.i32(o + 4);
        o += 8;
        check(offset >= 0 && offset < CHUNK_BUFFER && length >= 0 && o + length <= bufferEnd,
            "Invalid buffer bounds");
        patches.put(j + ":" + offset, b.slice(o, length));
        o += length;
      }
    }
    check(o == bufferEnd, "Unexpected buffer trailing data");

    Layout layout = new Layout(b, archetypes, chunkNode.start(), chunkCount);
    Singleton killed = layout.singleton(KILLED_ENEMIES_TYPE, KILLED_ENEMIES_COMPONENT_SIZE);
    int recordCount = b.i32(killed.at() + 8);
    int capacity = b.i32(killed.at() + 12);
    check(recordCount >= 0 && capacity >= recordCount && capacity >= KILLED_ENEMIES_INLINE_CAPACITY
        && b.i64(killed.at()) == 0L, "Invalid object buffer");
    byte[] external = patches.get(killed.chunk() + ":" + killed.offset());
    check(external != null ? external.length == (long) capacity * RECORD_SIZE
        : capacity == KILLED_ENEMIES_INLINE_CAPACITY, "Unsupported object buffer capacity");
    Buffer data = external != null ? new Buffer(external)
        : new Buffer(b.slice(killed.at() + 16, KILLED_ENEMIES_INLINE_CAPACITY * RECORD_SIZE));
    check((long) recordCount * RECORD_SIZE <= data.length(), "Truncated object buffer");
    List<CoreKeeperWorld.RecordedObject> records = new ArrayList<>();
    for (int j = 0; j < recordCount; j++) {
      records.add(new CoreKeeperWorld.RecordedObject(data.i32((long) j * RECORD_SIZE),
          data.i32((long) j * RECORD_SIZE + 4), data.i32((long) j * RECORD_SIZE + 8)));
    }

    Singleton wall = layout.singleton(GREAT_WALL_TYPE, 1);
    int wallValue = b.u8(wall.at());
    check(wallValue <= 1, "Invalid wall boolean");

    Map<String, Boolean> bosses = new LinkedHashMap<>();
    GAME_LOGIC_BOSS_IDS.forEach((name, id) ->
        bosses.put(name, records.stream().anyMatch(record -> record.objectId() == id)));
    Generation generation = generation(b);
    return new CoreKeeperWorld(sha256(input), version, b.length(), wallValue == 1,
        List.copyOf(records), Map.copyOf(bosses), generation.globalSeed(), generation.json());
  }

  byte[] decompress(byte[] input) {
    try (var stream = new BrotliInputStream(new ByteArrayInputStream(input))) {
      ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, input.length * 8));
      byte[] chunk = new byte[64 * 1024];
      long total = 0;
      for (int read; (read = stream.read(chunk)) > 0; ) {
        total += read;
        if (total > maxDecompressedBytes) {
          throw new CoreKeeperFormatException("Decompressed data exceeds its size limit");
        }
        output.write(chunk, 0, read);
      }
      return output.toByteArray();
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof CoreKeeperFormatException format) throw format;
      throw new CoreKeeperFormatException("Brotli decompression failed");
    }
  }

  /** World generation JSON is length-prefixed; it is informational and never fails the parse. */
  private Generation generation(Buffer b) {
    int at = b.indexOf(GENERATION_PREFIX);
    if (at < 4) return new Generation(null, null);
    int length = b.i32(at - 4);
    if (length <= 0 || length > 1024 * 1024 || at + (long) length > b.length()) {
      return new Generation(null, null);
    }
    String json = new String(b.slice(at, length), StandardCharsets.UTF_8);
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode seed = root.get("globalSeed");
      return new Generation(seed != null && seed.isIntegralNumber() ? seed.asLong() : null,
          objectMapper.writeValueAsString(root));
    } catch (RuntimeException exception) {
      return new Generation(null, null);
    }
  }

  private static Node node(Map<String, Node> nodes, String id, long nodeStart) {
    Node node = nodes.get(id);
    check(node != null && Integer.valueOf(1).equals(node.revision()) && node.start() >= HEADER_SIZE
        && node.size() >= 0 && node.start() + node.size() <= nodeStart,
        "Unsupported/missing node " + id);
    return node;
  }

  /** Matches the prototype's hex rendering: each byte's two hex digits are swapped. */
  private static String nodeId(Buffer b, long offset) {
    String hex = HexFormat.of().withUpperCase().formatHex(b.slice(offset, 16));
    StringBuilder swapped = new StringBuilder(hex.length());
    for (int j = 0; j < hex.length(); j += 2) swapped.append(hex.charAt(j + 1)).append(hex.charAt(j));
    return swapped.toString();
  }

  private static String sha256(byte[] input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new CoreKeeperFormatException(message);
  }

  private static Map<String, Integer> orderedBosses() {
    Map<String, Integer> bosses = new LinkedHashMap<>();
    bosses.put("CoreBoss", 3157);
    bosses.put("WallBoss", 3168);
    bosses.put("BirdBoss", 3090);
    bosses.put("OctopusBoss", 3110);
    bosses.put("ScarabBoss", 3131);
    bosses.put("HydraBossNature", 3145);
    bosses.put("HydraBossSea", 3146);
    bosses.put("HydraBossDesert", 3147);
    bosses.put("GiantCicadaBoss", 3183);
    bosses.put("RobotBoss", 3190);
    return java.util.Collections.unmodifiableMap(bosses);
  }

  private record Node(long start, long size, Integer revision) { }

  private record Archetype(int count, long[] types) {
    boolean has(long type) {
      for (long candidate : types) if (candidate == type) return true;
      return false;
    }
  }

  private record Singleton(int chunk, int offset, long at) { }

  private record Generation(Long globalSeed, String json) { }

  private record Layout(Buffer b, List<Archetype> archetypes, long chunkStart, int chunkCount) {
    Singleton singleton(long type, int componentSize) {
      int archetypeIndex = -1;
      for (int j = 0; j < archetypes.size(); j++) {
        if (archetypes.get(j).has(type)) {
          check(archetypeIndex < 0, "Expected one target archetype");
          archetypeIndex = j;
        }
      }
      check(archetypeIndex >= 0, "Expected one target archetype");
      Archetype archetype = archetypes.get(archetypeIndex);
      check(archetype.count() == 1 && archetype.types().length == 3 && archetype.has(ENTITY_TYPE)
          && archetype.has(SINGLETON_TAG_TYPE), "Unsupported singleton layout");
      int capacity = CHUNK_BUFFER / (8 + componentSize);
      while (align(8L * capacity) + align((long) componentSize * capacity) > CHUNK_BUFFER) {
        capacity--;
      }
      int offset = Math.toIntExact(align(8L * capacity));
      Singleton found = null;
      for (int k = 0; k < chunkCount; k++) {
        long base = chunkStart + (long) k * CHUNK_SIZE;
        if (b.i32(base) == archetypeIndex) {
          check(found == null, "Invalid singleton chunk");
          found = new Singleton(k, offset, base + 64 + offset);
          check(b.i32(base + 16) == 1, "Invalid singleton chunk");
        }
      }
      check(found != null, "Invalid singleton chunk");
      return found;
    }

    private static long align(long value) {
      return (value + 63) / 64 * 64;
    }
  }

  /** Little-endian reads that report malformed offsets as format errors. */
  private static final class Buffer {
    private final byte[] bytes;
    private final ByteBuffer view;

    Buffer(byte[] bytes) {
      this.bytes = bytes;
      this.view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    int length() {
      return bytes.length;
    }

    int i32(long offset) {
      return view.getInt(index(offset, 4));
    }

    long i64(long offset) {
      return view.getLong(index(offset, 8));
    }

    int u8(long offset) {
      return bytes[index(offset, 1)] & 0xff;
    }

    byte[] slice(long offset, long length) {
      int start = index(offset, length);
      byte[] copy = new byte[(int) length];
      System.arraycopy(bytes, start, copy, 0, (int) length);
      return copy;
    }

    boolean startsWith(byte[] prefix) {
      for (int j = 0; j < prefix.length; j++) if (bytes[j] != prefix[j]) return false;
      return true;
    }

    int indexOf(byte[] needle) {
      outer:
      for (int j = 0; j + needle.length <= bytes.length; j++) {
        for (int k = 0; k < needle.length; k++) if (bytes[j + k] != needle[k]) continue outer;
        return j;
      }
      return -1;
    }

    private int index(long offset, long length) {
      check(offset >= 0 && length >= 0 && offset + length <= bytes.length, "Truncated data");
      return (int) offset;
    }
  }
}
