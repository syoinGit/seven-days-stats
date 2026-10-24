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
  public static final String PARSER_VERSION = "ck-dotsbin77-3";
  public static final int CONTAINER_VERSION = 77;

  private static final byte[] MAGIC = "DOTSBIN!".getBytes(StandardCharsets.US_ASCII);
  private static final int HEADER_SIZE = 152;
  private static final int CHUNK_SIZE = 16384;
  private static final int CHUNK_BUFFER = CoreKeeperTypes.CHUNK_BUFFER;
  private static final String ARCHETYPE_NODE = "F5364E1CCB62466A9883F6F9554D4F0C";
  private static final String CHUNK_NODE = "2EA7CE3325F04D7A84CEAB46790C628A";
  private static final String BUFFER_NODE = "E33124BFAC2649D792DE36E4611DDD70";
  private static final int RECORD_SIZE = 12;
  private static final int MAX_OBJECT_ID = 1_000_000;
  private static final int MAX_LABEL_BYTES = 1024;
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

    Entities entities = readEntities(b, archetypes, patches, chunkNode.start(), chunkCount);
    Map<String, Boolean> bosses = new LinkedHashMap<>();
    GAME_LOGIC_BOSS_IDS.forEach((name, id) -> bosses.put(name,
        entities.killed().stream().anyMatch(record -> record.objectId() == id)));
    Generation generation = generation(b);
    return new CoreKeeperWorld(sha256(input), version, b.length(), entities.greatWallLowered(),
        List.copyOf(entities.killed()), Map.copyOf(bosses), generation.globalSeed(),
        generation.json(), List.copyOf(entities.containers()), entities.skipped());
  }

  private record Entities(List<CoreKeeperWorld.RecordedObject> killed, boolean greatWallLowered,
      List<CoreKeeperWorld.Container> containers, int skipped) { }

  /** Decodes every chunk whose archetype is fully known; the rest are counted as skipped. */
  private Entities readEntities(Buffer b, List<Archetype> archetypes, Map<String, byte[]> patches,
      long chunkStart, int chunkCount) {
    Map<Integer, List<Integer>> chunksByArchetype = new HashMap<>();
    int[] seen = new int[archetypes.size()];
    for (int k = 0; k < chunkCount; k++) {
      long base = chunkStart + (long) k * CHUNK_SIZE;
      int archetype = b.i32(base);
      int count = b.i32(base + 16);
      check(archetype >= 0 && archetype < archetypes.size() && count >= 0
          && count <= CoreKeeperTypes.MAX_CHUNK_CAPACITY, "Invalid chunk header");
      seen[archetype] += count;
      chunksByArchetype.computeIfAbsent(archetype, key -> new ArrayList<>()).add(k);
    }
    for (int j = 0; j < archetypes.size(); j++) {
      check(seen[j] == archetypes.get(j).count(), "Chunk entity counts do not match archetypes");
    }

    List<CoreKeeperWorld.RecordedObject> killed = null;
    Boolean wall = null;
    List<CoreKeeperWorld.Container> containers = new ArrayList<>();
    int skipped = 0;
    for (int j = 0; j < archetypes.size(); j++) {
      Archetype archetype = archetypes.get(j);
      if (archetype.count() == 0) continue;
      CoreKeeperTypes.Layout layout = CoreKeeperTypes.layout(archetype.types());
      if (layout == null) {
        skipped += archetype.count();
        continue;
      }
      for (int k : chunksByArchetype.getOrDefault(j, List.of())) {
        long data = chunkStart + (long) k * CHUNK_SIZE + 64;
        int count = b.i32(data - 64 + 16);
        check(count <= layout.capacity(), "Chunk holds more entities than its archetype allows");
        Chunk chunk = new Chunk(b, patches, k, data, layout);
        if (layout.has(CoreKeeperTypes.KILLED_ENEMIES)) {
          check(killed == null && count == 1, "Expected one killed-objects record");
          killed = records(chunk.buffer(CoreKeeperTypes.KILLED_ENEMIES, 0));
        }
        if (layout.has(CoreKeeperTypes.GREAT_WALL)) {
          check(wall == null && count == 1, "Expected one wall record");
          int value = b.u8(chunk.at(CoreKeeperTypes.GREAT_WALL, 0));
          check(value <= 1, "Invalid wall boolean");
          wall = value == 1;
        }
        if (!layout.has(CoreKeeperTypes.OBJECT_DATA)) continue;
        for (int e = 0; e < count; e++) {
          long object = chunk.at(CoreKeeperTypes.OBJECT_DATA, e);
          int objectId = b.i32(object);
          check(objectId >= 0 && objectId < MAX_OBJECT_ID, "Unexpected object id");
          if (layout.has(CoreKeeperTypes.CONTAINED_OBJECTS)) {
            containers.add(container(chunk, e, objectId, b.i32(object + 8)));
          }
        }
      }
    }
    check(killed != null && wall != null, "Expected world records");
    containers.sort(java.util.Comparator.comparingInt(CoreKeeperWorld.Container::x)
        .thenComparingInt(CoreKeeperWorld.Container::z)
        .thenComparingInt(CoreKeeperWorld.Container::objectId));
    return new Entities(killed, wall, containers, skipped);
  }

  private static CoreKeeperWorld.Container container(Chunk chunk, int entity, int objectId,
      int variation) {
    check(chunk.layout().has(CoreKeeperTypes.TRANSLATION), "Container without a position");
    long position = chunk.at(CoreKeeperTypes.TRANSLATION, entity);
    float x = chunk.b().f32(position);
    float z = chunk.b().f32(position + 8);
    check(Float.isFinite(x) && Float.isFinite(z) && Math.abs(x) < 1e6 && Math.abs(z) < 1e6,
        "Unexpected position");
    Buffer slots = chunk.buffer(CoreKeeperTypes.CONTAINED_OBJECTS, entity);
    int slotCount = slots.length() / RECORD_SIZE;
    List<CoreKeeperWorld.Slot> items = new ArrayList<>();
    for (int slot = 0; slot < slotCount; slot++) {
      int id = slots.i32((long) slot * RECORD_SIZE);
      check(id >= 0 && id < MAX_OBJECT_ID, "Unexpected contained object id");
      if (id == 0) continue;
      items.add(new CoreKeeperWorld.Slot(slot, id, slots.i32((long) slot * RECORD_SIZE + 4),
          slots.i32((long) slot * RECORD_SIZE + 8)));
    }
    CoreKeeperTypes.Layout layout = chunk.layout();
    String holder = layout.has(CoreKeeperTypes.CONDITIONS) ? "NPC"
        : layout.has(CoreKeeperTypes.CLAIMED_BY_PLAYER) ? "GRAVE"
        : layout.has(CoreKeeperTypes.HEALTH) ? "STORAGE" : "LOOSE";
    String label = layout.has(CoreKeeperTypes.DESCRIPTION)
        ? label(chunk.buffer(CoreKeeperTypes.DESCRIPTION, entity).bytes()) : null;
    return new CoreKeeperWorld.Container(holder, objectId, variation, Math.round(x), Math.round(z),
        slotCount, List.copyOf(items), label);
  }

  /** A chest label as the game stores it: UTF-8 bytes. Blank labels count as none. */
  static String label(byte[] bytes) {
    check(bytes.length <= MAX_LABEL_BYTES, "Label too long");
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw new CoreKeeperFormatException("Label is not UTF-8");
    }
    String cleaned = text.replaceAll("\\p{Cntrl}", " ").strip();
    return cleaned.isEmpty() ? null : cleaned;
  }

  private static List<CoreKeeperWorld.RecordedObject> records(Buffer data) {
    List<CoreKeeperWorld.RecordedObject> records = new ArrayList<>();
    for (int j = 0; j < data.length() / RECORD_SIZE; j++) {
      records.add(new CoreKeeperWorld.RecordedObject(data.i32((long) j * RECORD_SIZE),
          data.i32((long) j * RECORD_SIZE + 4), data.i32((long) j * RECORD_SIZE + 8)));
    }
    return records;
  }

  /** One chunk's data area; offsets come from the archetype layout. */
  private record Chunk(Buffer b, Map<String, byte[]> patches, int index, long data,
      CoreKeeperTypes.Layout layout) {
    long at(long type, int entity) {
      return data + layout.offset(type)
          + (long) CoreKeeperTypes.type(type).bytesInChunk() * entity;
    }

    /**
     * A dynamic buffer's used elements. Small buffers live inside the chunk; once grown past
     * their inline capacity the elements are stored as a patch keyed by chunk and offset.
     */
    Buffer buffer(long type, int entity) {
      CoreKeeperTypes.Type info = CoreKeeperTypes.type(type);
      int offset = Math.toIntExact(layout.offset(type) + (long) info.bytesInChunk() * entity);
      long at = data + offset;
      long pointer = b.i64(at);
      int length = b.i32(at + 8);
      int capacity = b.i32(at + 12);
      check(pointer == 0L && length >= 0 && capacity >= info.inlineCapacity() && length <= capacity,
          "Invalid buffer header");
      long bytes = (long) length * info.size();
      if (capacity == info.inlineCapacity()) return new Buffer(b.slice(at + 16, bytes));
      byte[] external = patches.get(index + ":" + offset);
      check(external != null && external.length >= bytes, "Missing buffer data");
      return new Buffer(java.util.Arrays.copyOf(external, (int) bytes));
    }
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

  private record Generation(Long globalSeed, String json) { }

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

    byte[] bytes() {
      return bytes.clone();
    }

    int i32(long offset) {
      return view.getInt(index(offset, 4));
    }

    long i64(long offset) {
      return view.getLong(index(offset, 8));
    }

    float f32(long offset) {
      return view.getFloat(index(offset, 4));
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
