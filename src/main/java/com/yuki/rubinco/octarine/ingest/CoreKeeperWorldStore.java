package com.yuki.rubinco.octarine.ingest;

import com.yuki.rubinco.octarine.ingest.CoreKeeperWorld.Container;
import com.yuki.rubinco.worldprogress.service.WorldSnapshotRecorder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a snapshot and the Core Keeper tables in one transaction, so pages never mix contents
 * from two saves. The first snapshot of a world is a baseline and logs no item changes.
 *
 * <p>The save does not record who placed or opened a container. A container counts as known to
 * the players once its contents differ from the first time the site saw it: a chest someone
 * placed and filled, or a dungeon chest someone looted. Only known containers are stored and
 * totalled; merchants' stock is never stored.
 */
@Service
@RequiredArgsConstructor
public class CoreKeeperWorldStore {
  private final WorldSnapshotRecorder recorder;
  private final JdbcTemplate jdbc;

  public record Stored(Long snapshotId, int progressEvents, int itemChanges) { }

  /** Stored totals per item: amount sum, filled slots and holding containers. */
  record ItemTotal(long amount, int slots, int containers) { }

  record Key(int objectId, int x, int z) {
    static Key of(Container container) {
      return new Key(container.objectId(), container.x(), container.z());
    }
  }

  @Transactional
  public Optional<Stored> store(Long worldId, boolean baseline, CoreKeeperWorld world,
      WorldSnapshotRecorder.SnapshotInput input) {
    var recorded = recorder.record(worldId, input);
    if (recorded.isEmpty()) return Optional.empty();
    Long snapshotId = recorded.get().snapshot().getId();
    OffsetDateTime now = OffsetDateTime.now();
    List<Container> known = trackContainers(worldId, world.containers(), now);
    replaceContainers(worldId, snapshotId, known);
    int itemChanges = updateItemTotals(worldId, snapshotId, known, baseline, now);
    return Optional.of(new Stored(snapshotId, recorded.get().events().size(), itemChanges));
  }

  /**
   * Records first sightings and returns the storage containers known to the players. Two
   * containers on one tile cannot be told apart between saves, so they are never known.
   */
  private List<Container> trackContainers(Long worldId, List<Container> containers,
      OffsetDateTime now) {
    Map<Key, List<Container>> byKey = new HashMap<>();
    containers.stream().filter(container -> "STORAGE".equals(container.holder()))
        .forEach(container -> byKey.computeIfAbsent(Key.of(container), key -> new ArrayList<>()).add(container));
    Map<Key, String> firstHashes = new HashMap<>();
    Set<Key> known = new HashSet<>();
    jdbc.query("SELECT object_id, pos_x, pos_z, first_content_hash, known_at FROM T_CK_CONTAINER_TRACK "
        + "WHERE game_world_id = ?", rs -> {
          Key key = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3));
          firstHashes.put(key, rs.getString(4));
          if (rs.getTimestamp(5) != null) known.add(key);
        }, worldId);
    Timestamp at = Timestamp.from(now.toInstant());
    List<Object[]> inserts = new ArrayList<>();
    List<Object[]> nowKnown = new ArrayList<>();
    List<Container> result = new ArrayList<>();
    for (Container container : containers) {
      Key key = Key.of(container);
      if (!"STORAGE".equals(container.holder()) || byKey.get(key).size() != 1) continue;
      String hash = contentHash(container);
      String first = firstHashes.get(key);
      if (first == null) {
        inserts.add(new Object[] {worldId, key.objectId(), key.x(), key.z(), hash, at});
        continue;
      }
      if (!known.contains(key)) {
        if (first.equals(hash)) continue;
        nowKnown.add(new Object[] {at, worldId, key.objectId(), key.x(), key.z()});
      }
      result.add(container);
    }
    jdbc.batchUpdate("INSERT INTO T_CK_CONTAINER_TRACK (game_world_id, object_id, pos_x, pos_z, "
        + "first_content_hash, first_seen_at) VALUES (?, ?, ?, ?, ?, ?)", inserts);
    jdbc.batchUpdate("UPDATE T_CK_CONTAINER_TRACK SET known_at = ? WHERE game_world_id = ? AND object_id = ? "
        + "AND pos_x = ? AND pos_z = ?", nowKnown);
    return result;
  }

  static String contentHash(Container container) {
    StringBuilder content = new StringBuilder();
    container.items().forEach(item -> content.append(item.slot()).append(',').append(item.objectId())
        .append(',').append(item.amount()).append(',').append(item.variation()).append(';'));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(content.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static Map<Integer, ItemTotal> itemTotals(List<Container> containers) {
    Map<Integer, long[]> sums = new HashMap<>();
    Map<Integer, Set<Container>> holders = new HashMap<>();
    for (Container container : containers) {
      container.items().forEach(item -> {
        long[] sum = sums.computeIfAbsent(item.objectId(), id -> new long[2]);
        sum[0] += item.amount();
        sum[1]++;
        holders.computeIfAbsent(item.objectId(), id -> new HashSet<>()).add(container);
      });
    }
    Map<Integer, ItemTotal> totals = new HashMap<>();
    sums.forEach((id, sum) -> totals.put(id,
        new ItemTotal(sum[0], (int) sum[1], holders.get(id).size())));
    return totals;
  }

  private void replaceContainers(Long worldId, Long snapshotId, List<Container> containers) {
    jdbc.update("DELETE FROM T_CK_CONTAINER_ITEM WHERE game_world_id = ?", worldId);
    jdbc.update("DELETE FROM T_CK_CONTAINER WHERE game_world_id = ?", worldId);
    SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbc).withTableName("T_CK_CONTAINER")
        .usingGeneratedKeyColumns("ck_container_id")
        .usingColumns("game_world_id", "world_snapshot_id", "object_id", "variation",
            "pos_x", "pos_z", "slot_count", "filled_slots");
    List<Object[]> items = new ArrayList<>();
    for (Container container : containers) {
      Map<String, Object> row = new HashMap<>();
      row.put("game_world_id", worldId);
      row.put("world_snapshot_id", snapshotId);
      row.put("object_id", container.objectId());
      row.put("variation", container.variation());
      row.put("pos_x", container.x());
      row.put("pos_z", container.z());
      row.put("slot_count", container.slotCount());
      row.put("filled_slots", container.items().size());
      long containerId = insert.executeAndReturnKey(row).longValue();
      container.items().forEach(item -> items.add(new Object[] {
          containerId, worldId, item.slot(), item.objectId(), item.amount(), item.variation()}));
    }
    jdbc.batchUpdate("INSERT INTO T_CK_CONTAINER_ITEM "
        + "(ck_container_id, game_world_id, slot, object_id, amount, variation) VALUES (?, ?, ?, ?, ?, ?)",
        items);
  }

  private int updateItemTotals(Long worldId, Long snapshotId, List<Container> known,
      boolean baseline, OffsetDateTime now) {
    Map<Integer, ItemTotal> totals = itemTotals(known);
    Map<Integer, ItemTotal> existing = new HashMap<>();
    jdbc.query("SELECT object_id, total_amount, slot_count, container_count FROM T_CK_ITEM_TOTAL "
        + "WHERE game_world_id = ?", rs -> {
          existing.put(rs.getInt(1), new ItemTotal(rs.getLong(2), rs.getInt(3), rs.getInt(4)));
        }, worldId);
    Timestamp at = Timestamp.from(now.toInstant());
    ItemTotal none = new ItemTotal(0, 0, 0);
    List<Object[]> inserts = new ArrayList<>();
    List<Object[]> updates = new ArrayList<>();
    List<Object[]> changes = new ArrayList<>();
    Set<Integer> ids = new TreeSet<>(totals.keySet());
    ids.addAll(existing.keySet());
    for (int id : ids) {
      ItemTotal total = totals.getOrDefault(id, none);
      ItemTotal previous = existing.get(id);
      if (previous == null) {
        inserts.add(new Object[] {worldId, id, total.amount(), total.slots(), total.containers(), at, at});
        if (!baseline) changes.add(new Object[] {worldId, snapshotId, id, 0L, total.amount(), 0, total.slots(), at});
      } else if (previous.amount() != total.amount() || previous.slots() != total.slots()) {
        updates.add(new Object[] {total.amount(), total.slots(), total.containers(), at, worldId, id});
        if (!baseline) {
          changes.add(new Object[] {worldId, snapshotId, id, previous.amount(), total.amount(),
              previous.slots(), total.slots(), at});
        }
      } else if (previous.containers() != total.containers()) {
        jdbc.update("UPDATE T_CK_ITEM_TOTAL SET container_count = ? WHERE game_world_id = ? AND object_id = ?",
            total.containers(), worldId, id);
      }
    }
    jdbc.batchUpdate("INSERT INTO T_CK_ITEM_TOTAL (game_world_id, object_id, total_amount, slot_count, "
        + "container_count, first_seen_at, last_changed_at) VALUES (?, ?, ?, ?, ?, ?, ?)", inserts);
    jdbc.batchUpdate("UPDATE T_CK_ITEM_TOTAL SET total_amount = ?, slot_count = ?, container_count = ?, "
        + "last_changed_at = ? WHERE game_world_id = ? AND object_id = ?", updates);
    jdbc.batchUpdate("INSERT INTO T_CK_ITEM_CHANGE (game_world_id, world_snapshot_id, object_id, "
        + "previous_amount, new_amount, previous_slots, new_slots, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        changes);
    return changes.size();
  }
}
