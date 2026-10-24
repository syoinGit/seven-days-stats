package com.yuki.rubinco.octarine.ingest;

import java.util.List;
import java.util.Map;

/**
 * Values read from one save. {@code recordedObjects} amounts are stored game values; they are not
 * verified kill or mining counts and the list also contains non-enemy objects.
 *
 * <p>Container slot amounts are the game's stored amount: a stack size for materials, but the
 * remaining durability for tools, weapons and armour. The save does not say which is which.
 */
public record CoreKeeperWorld(
    String sourceSha256,
    int containerVersion,
    long decompressedBytes,
    boolean greatWallLowered,
    List<RecordedObject> recordedObjects,
    Map<String, Boolean> bossKilledFromGameLogic,
    Long globalSeed,
    String generationJson,
    List<Container> containers,
    int skippedEntities) {

  public boolean hasRecordedObject(int objectId) {
    return recordedObjects.stream().anyMatch(record -> record.objectId() == objectId);
  }

  public record RecordedObject(int objectId, int amount, int variation) { }

  /**
   * Anything holding items. {@code holder} is NPC for creatures that carry stock (merchants) and
   * STORAGE for everything else: chests, pots, pedestals, dropped items, graves.
   */
  public record Container(String holder, int objectId, int variation, int x, int z,
      int slotCount, List<Slot> items) { }

  /** One filled slot. Empty slots (object id 0) are not listed. */
  public record Slot(int slot, int objectId, int amount, int variation) { }
}
