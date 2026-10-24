package com.yuki.rubinco.octarine.ingest;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Component types that can appear in a serialized Core Keeper world, keyed by Unity's stable type
 * hash, and the Unity Entities rules that place them inside a 16 KB chunk.
 *
 * <p>Sizes, buffer capacities and memory orderings come from the game's
 * {@code OverrideTypeHash}/{@code ForcedMemoryOrdering} attributes. Entries marked inferred have
 * no override in the game code; their size was found by decoding every chunk of a sample world
 * and is re-checked on each parse (an archetype whose data does not validate fails the parse).
 * An archetype that contains a type not listed here is skipped and counted, never guessed.
 */
final class CoreKeeperTypes {
  static final int CHUNK_BUFFER = 16320;
  static final int MAX_CHUNK_CAPACITY = 128;
  private static final int ENTITY_SIZE = 8;
  private static final int BUFFER_HEADER = 16;
  private static final int DEFAULT_BUFFER_BYTES = 128;
  private static final long NO_ENTITY_REFERENCES = 131072L;
  private static final long ENABLEABLE = 16777216L;
  private static final long BUFFER = 67108864L;
  private static final long ZERO_SIZE = 1073741824L;

  enum Kind { COMPONENT, BUFFER, TAG }

  /**
   * @param size component size, or buffer element size
   * @param declaredCapacity InternalBufferCapacity; null means Unity's default
   * @param memoryOrder ForcedMemoryOrdering, or null to order by the stable hash
   */
  record Type(String name, Kind kind, int size, Integer declaredCapacity, Long memoryOrder,
      boolean enableable, boolean inferred) {
    int bytesInChunk() {
      return switch (kind) {
        case TAG -> 0;
        case COMPONENT -> size;
        case BUFFER -> BUFFER_HEADER + inlineCapacity() * size;
      };
    }

    int inlineCapacity() {
      if (declaredCapacity != null) return declaredCapacity;
      return size == 0 ? 0 : DEFAULT_BUFFER_BYTES / size;
    }
  }

  static final long SIMULATE = hash("2308415037858323387");
  static final long SINGLETON_TAG = hash("8089487776991448321");
  static final long OBJECT_DATA = hash("1510908940499422986");
  static final long TRANSLATION = hash("5560902727204706568");
  static final long HEALTH = hash("9718905805775528276");
  static final long CLAIMED_BY_PLAYER = hash("5201058450301913387");
  static final long CONDITIONS = hash("10190325920577701639");
  static final long CONTAINED_OBJECTS = hash("8817826183114497276");
  static final long KILLED_ENEMIES = hash("3317929161124881020");
  static final long GREAT_WALL = hash("13321463714558854008");

  private static final Map<Long, Type> TYPES = types();

  private CoreKeeperTypes() {
  }

  static Type type(long hash) {
    return TYPES.get(hash);
  }

  /** Byte offsets (from the start of the chunk data area) of each type's array. */
  record Layout(int capacity, Map<Long, Integer> offsets) {
    int offset(long type) {
      Integer offset = offsets.get(type);
      if (offset == null) throw new CoreKeeperFormatException("Type missing from archetype");
      return offset;
    }

    boolean has(long type) {
      return offsets.containsKey(type);
    }
  }

  /**
   * Mirrors EntityComponentStore: the Entity array comes first, the rest are sorted by type flags
   * and memory ordering, each array is 64-byte aligned and capacity is capped at 128.
   * Returns null when an archetype contains an unknown type.
   */
  static Layout layout(long[] archetypeTypes) {
    List<Long> sorted = new java.util.ArrayList<>();
    for (long hash : archetypeTypes) {
      if (!TYPES.containsKey(hash)) return null;
      sorted.add(hash);
    }
    sorted.sort(Comparator.<Long>comparingLong(hash -> flags(TYPES.get(hash)))
        .thenComparing(hash -> order(hash), Long::compareUnsigned));
    int total = ENTITY_SIZE;
    for (long hash : sorted) total += TYPES.get(hash).bytesInChunk();
    int capacity = Math.min(MAX_CHUNK_CAPACITY, CHUNK_BUFFER / total);
    while (capacity > 0 && used(sorted, capacity) > CHUNK_BUFFER) capacity--;
    if (capacity <= 0) throw new CoreKeeperFormatException("Archetype does not fit a chunk");
    Map<Long, Integer> offsets = new HashMap<>();
    int offset = align(ENTITY_SIZE * capacity);
    for (long hash : sorted) {
      offsets.put(hash, offset);
      offset += align(TYPES.get(hash).bytesInChunk() * capacity);
    }
    return new Layout(capacity, Map.copyOf(offsets));
  }

  private static int used(List<Long> sorted, int capacity) {
    int used = align(ENTITY_SIZE * capacity);
    for (long hash : sorted) used += align(TYPES.get(hash).bytesInChunk() * capacity);
    return used;
  }

  private static long flags(Type type) {
    long flags = NO_ENTITY_REFERENCES;
    if (type.kind() == Kind.BUFFER) flags |= BUFFER;
    if (type.kind() == Kind.TAG) flags |= ZERO_SIZE;
    if (type.enableable()) flags |= ENABLEABLE;
    return flags;
  }

  private static long order(long hash) {
    Long forced = TYPES.get(hash).memoryOrder();
    return forced != null ? forced : hash;
  }

  private static int align(int value) {
    return (value + 63) / 64 * 64;
  }

  private static long hash(String unsigned) {
    return Long.parseUnsignedLong(unsigned);
  }

  private static Map<Long, Type> types() {
    Map<Long, Type> types = new HashMap<>();
    tag(types, "2308415037858323387", "Simulate", null, true);
    tag(types, "8089487776991448321", "SingletonTag", null, false);
    tag(types, "1820367538491280042", "CustomSceneObject", "13374821791400726150", false);
    component(types, "1510908940499422986", "ObjectData", 12, "4854830781492898327");
    component(types, "5560902727204706568", "Translation", 12, "11830366659655493054");
    component(types, "9718905805775528276", "Health", 4, "1933861636573918204");
    component(types, "7953502142579949517", "Rotation", 12, "14048376630969704601");
    component(types, "14841664605717691252", "CustomScene", 32, "9553928676620345146");
    component(types, "9668443093423200302", "DungeonName", 32, "2626522292662886804");
    component(types, "18221140013894219486", "Name", 64, "9923282613123898873");
    component(types, "4073445497772281464", "Growing", 8, "12343954618917922255");
    component(types, "5988274651001308395", "Hunger", 4, "3874981196126545681");
    component(types, "4485583542073257979", "BreedState", 4, "18362501804100791937");
    component(types, "3386982922340789878", "BreedToggle", 4, null);
    component(types, "1301047133137499053", "Paintable", 4, "2406913203039094111");
    component(types, "12052904543115273478", "PetSkin", 4, "13695103918181693450");
    component(types, "7474316307185981995", "ActiveEquipmentPreset", 4, "8843759668018188945");
    component(types, "8017971940887369152", "WorldVersion", 4, "3060899870865786024");
    component(types, "10614695436265067493", "ServerSeed", 4, "12990756329374269695");
    component(types, "13551751101155193141", "CharacterGuid", 16, "15774323510541663350");
    component(types, "10437320314668727757", "ClaimedByCharacterGuid", 16, "2590167599983830990");
    component(types, "5201058450301913387", "ClaimedByPlayerGuid", 16, "1526787339524341736");
    component(types, "13313812645501089251", "PlayerGuid", 16, "2238760741437376544");
    component(types, "2024114433828824476", "Player", 16, "16104261336207737813");
    component(types, "13966412220673641327", "ServerGuid", 16, "15786110304336860764");
    component(types, "17365962714334043572", "InventoryAuxData", 8, "12585783299483863923");
    component(types, "15506877059273323975", "LootTable", 4, "10675777891542292791");
    component(types, "9457240999318900283", "SnakeBossSegment", 8, "17952618990926802762");
    component(types, "8528839500490394020", "SpawnPoint", 12, "2077958247423801706");
    component(types, "13163596565325215301", "SubMap", 8, "18314970738154257525");
    component(types, "12451587167075373321", "Pheromone", 72, "664105384916819263");
    component(types, "13321463714558854008", "GreatWallStatus", 1, "2777190051010392676");
    component(types, "12956612845000496674", "WorldCreationVersion", 4, null);
    component(types, "15984297898415659816", "WorldGenerationType", 4, "6188522237308276814");
    buffer(types, "8817826183114497276", "ContainedObjects", 12, null, "4322402440950630264");
    buffer(types, "14567693678096925962", "ContainedObjectsAuxIndex", 4, null, "4323303741478397406");
    buffer(types, "10190325920577701639", "Conditions", 16, null, "6682437030782645294");
    buffer(types, "12149353869256961210", "DropsLoot", 8, null, "2075879875591593823");
    buffer(types, "2145835212504974577", "Description", 1, null, null);
    buffer(types, "3317929161124881020", "KilledEnemies", 12, null, "15231150560887796728");
    buffer(types, "8960563578729339433", "SubMapLayer", 0, 0, "17385121593422961126");
    buffer(types, "3873562367253663128", "Talents", 8, null, "16038764625220822319");
    // No override in the game code. Sizes found from data (see class comment).
    types.put(hash("10483360771428382674"),
        new Type("Unidentified12", Kind.COMPONENT, 12, null, null, false, true));
    types.put(hash("15759000181413222894"),
        new Type("UnidentifiedBufferA", Kind.BUFFER, 4, null, null, false, true));
    types.put(hash("15474012472996566401"),
        new Type("UnidentifiedBufferB", Kind.BUFFER, 4, null, null, false, true));
    return Map.copyOf(types);
  }

  private static void tag(Map<Long, Type> types, String hash, String name, String order,
      boolean enableable) {
    types.put(hash(hash), new Type(name, Kind.TAG, 0, null, order == null ? null : hash(order),
        enableable, false));
  }

  private static void component(Map<Long, Type> types, String hash, String name, int size,
      String order) {
    types.put(hash(hash), new Type(name, Kind.COMPONENT, size, null,
        order == null ? null : hash(order), false, false));
  }

  private static void buffer(Map<Long, Type> types, String hash, String name, int elementSize,
      Integer inlineCapacity, String order) {
    types.put(hash(hash), new Type(name, Kind.BUFFER, elementSize, inlineCapacity,
        order == null ? null : hash(order), false, false));
  }
}
