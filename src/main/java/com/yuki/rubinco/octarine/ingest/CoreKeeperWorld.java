package com.yuki.rubinco.octarine.ingest;

import java.util.List;
import java.util.Map;

/**
 * Values read from one save. {@code recordedObjects} amounts are stored game values; they are not
 * verified kill or mining counts and the list also contains non-enemy objects.
 */
public record CoreKeeperWorld(
    String sourceSha256,
    int containerVersion,
    long decompressedBytes,
    boolean greatWallLowered,
    List<RecordedObject> recordedObjects,
    Map<String, Boolean> bossKilledFromGameLogic,
    Long globalSeed,
    String generationJson) {

  public boolean hasRecordedObject(int objectId) {
    return recordedObjects.stream().anyMatch(record -> record.objectId() == objectId);
  }

  public record RecordedObject(int objectId, int amount, int variation) { }
}
