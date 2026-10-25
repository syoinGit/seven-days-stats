package com.yuki.rubinco.octarine.ingest;

import com.yuki.rubinco.octarine.ingest.CoreKeeperWorld.Container;
import com.yuki.rubinco.worldprogress.service.WorldSnapshotRecorder;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 * <p>Only player chests are stored: storage containers the players gave a label in game. World
 * chests, merchants' stock, graves and dropped items have no label and are never stored.
 *
 * <p>Changes describe what happened inside chests that were player chests in both saves. A chest
 * that gains a label joins quietly, and one that loses its label (or is picked up) leaves
 * quietly, so labelling a full chest does not show its contents as added.
 */
@Service
@RequiredArgsConstructor
public class CoreKeeperWorldStore {
  private final WorldSnapshotRecorder recorder;
  private final JdbcTemplate jdbc;

  public record Stored(Long snapshotId, int progressEvents, int itemChanges) { }

  /** Stored totals per item: amount sum, filled slots and holding containers. */
  record ItemTotal(long amount, int slots, int containers) { }

  /** A chest across saves: type and tile (two cannot share a tile). */
  record Key(int objectId, int x, int z) {
    static Key of(Container container) {
      return new Key(container.objectId(), container.x(), container.z());
    }
  }

  /** Per item in one chest: amount and filled slots. */
  record Held(long amount, int slots) { }

  @Transactional
  public Optional<Stored> store(Long worldId, boolean baseline, CoreKeeperWorld world,
      WorldSnapshotRecorder.SnapshotInput input) {
    var recorded = recorder.record(worldId, input);
    if (recorded.isEmpty()) return Optional.empty();
    Long snapshotId = recorded.get().snapshot().getId();
    OffsetDateTime now = OffsetDateTime.now();
    List<Container> chests = playerChests(world.containers());
    Map<Key, Integer> beforeCounts = new HashMap<>();
    Map<Key, Map<Integer, Held>> before = storedChests(worldId, beforeCounts);
    Map<Key, Integer> afterCounts = new HashMap<>();
    chests.forEach(chest -> afterCounts.merge(Key.of(chest), 1, Integer::sum));
    // Chests present once in both saves; everything else joins or leaves quietly.
    Set<Key> continuing = new HashSet<>(before.keySet());
    continuing.removeIf(key -> beforeCounts.get(key) != 1 || afterCounts.getOrDefault(key, 0) != 1);
    if (!baseline) logChestChanges(worldId, snapshotId, chests, before, continuing, now);
    replaceContainers(worldId, snapshotId, chests);
    int itemChanges = updateItemTotals(worldId, snapshotId, chests, before, continuing, baseline, now);
    return Optional.of(new Stored(snapshotId, recorded.get().events().size(), itemChanges));
  }

  static List<Container> playerChests(List<Container> containers) {
    return containers.stream()
        .filter(container -> "STORAGE".equals(container.holder()) && container.label() != null)
        .toList();
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

  private static Map<Integer, Held> held(Container container) {
    Map<Integer, Held> result = new TreeMap<>();
    container.items().forEach(item -> result.merge(item.objectId(), new Held(item.amount(), 1),
        (a, b) -> new Held(a.amount() + b.amount(), a.slots() + b.slots())));
    return result;
  }

  /** The previous save's player chests, read before they are replaced. */
  private Map<Key, Map<Integer, Held>> storedChests(Long worldId, Map<Key, Integer> counts) {
    jdbc.query("SELECT object_id, pos_x, pos_z FROM T_CK_CONTAINER WHERE game_world_id = ?", rs -> {
      counts.merge(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), 1, Integer::sum);
    }, worldId);
    Map<Key, Map<Integer, Held>> result = new HashMap<>();
    jdbc.query("SELECT c.object_id, c.pos_x, c.pos_z, i.object_id, i.amount FROM T_CK_CONTAINER c "
        + "LEFT JOIN T_CK_CONTAINER_ITEM i ON i.ck_container_id = c.ck_container_id "
        + "WHERE c.game_world_id = ?", rs -> {
          Map<Integer, Held> items = result.computeIfAbsent(
              new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), key -> new TreeMap<>());
          int objectId = rs.getInt(4);
          if (!rs.wasNull()) {
            items.merge(objectId, new Held(rs.getLong(5), 1),
                (a, b) -> new Held(a.amount() + b.amount(), a.slots() + b.slots()));
          }
        }, worldId);
    return result;
  }

  private void logChestChanges(Long worldId, Long snapshotId, List<Container> chests,
      Map<Key, Map<Integer, Held>> before, Set<Key> continuing, OffsetDateTime now) {
    Timestamp at = Timestamp.from(now.toInstant());
    Held none = new Held(0, 0);
    List<Object[]> rows = new ArrayList<>();
    for (Container chest : chests) {
      Key key = Key.of(chest);
      if (!continuing.contains(key)) continue;
      Map<Integer, Held> previous = before.get(key);
      Map<Integer, Held> current = held(chest);
      Set<Integer> ids = new TreeSet<>(previous.keySet());
      ids.addAll(current.keySet());
      for (int id : ids) {
        Held from = previous.getOrDefault(id, none);
        Held to = current.getOrDefault(id, none);
        if (from.amount() != to.amount() || from.slots() != to.slots()) {
          rows.add(new Object[] {worldId, snapshotId, key.objectId(), key.x(), key.z(), chest.label(),
              id, from.amount(), to.amount(), from.slots(), to.slots(), at});
        }
      }
    }
    jdbc.batchUpdate("INSERT INTO T_CK_CHEST_CHANGE (game_world_id, world_snapshot_id, chest_object_id, "
        + "pos_x, pos_z, label, object_id, previous_amount, new_amount, previous_slots, new_slots, observed_at) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
  }

  private void replaceContainers(Long worldId, Long snapshotId, List<Container> containers) {
    jdbc.update("DELETE FROM T_CK_CONTAINER_ITEM WHERE game_world_id = ?", worldId);
    jdbc.update("DELETE FROM T_CK_CONTAINER WHERE game_world_id = ?", worldId);
    SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbc).withTableName("T_CK_CONTAINER")
        .usingGeneratedKeyColumns("ck_container_id")
        .usingColumns("game_world_id", "world_snapshot_id", "holder", "object_id", "variation",
            "pos_x", "pos_z", "slot_count", "filled_slots", "label");
    List<Object[]> items = new ArrayList<>();
    for (Container container : containers) {
      Map<String, Object> row = new HashMap<>();
      row.put("game_world_id", worldId);
      row.put("world_snapshot_id", snapshotId);
      row.put("holder", container.holder());
      row.put("object_id", container.objectId());
      row.put("variation", container.variation());
      row.put("pos_x", container.x());
      row.put("pos_z", container.z());
      row.put("slot_count", container.slotCount());
      row.put("filled_slots", container.items().size());
      row.put("label", container.label());
      long containerId = insert.executeAndReturnKey(row).longValue();
      container.items().forEach(item -> items.add(new Object[] {
          containerId, worldId, item.slot(), item.objectId(), item.amount(), item.variation()}));
    }
    jdbc.batchUpdate("INSERT INTO T_CK_CONTAINER_ITEM "
        + "(ck_container_id, game_world_id, slot, object_id, amount, variation) VALUES (?, ?, ?, ?, ?, ?)",
        items);
  }

  private int updateItemTotals(Long worldId, Long snapshotId, List<Container> chests,
      Map<Key, Map<Integer, Held>> before, Set<Key> continuing, boolean baseline, OffsetDateTime now) {
    Map<Integer, ItemTotal> totals = itemTotals(chests);
    // What joined or left quietly, per item; subtracted from the logged change.
    Map<Integer, long[]> quiet = new HashMap<>();
    chests.forEach(chest -> {
      if (!continuing.contains(Key.of(chest))) held(chest).forEach((id, held) -> add(quiet, id, held, 1));
    });
    before.forEach((key, items) -> {
      if (!continuing.contains(key)) items.forEach((id, held) -> add(quiet, id, held, -1));
    });
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
      ItemTotal from = previous == null ? none : previous;
      long[] moved = quiet.getOrDefault(id, new long[2]);
      long fromAmount = from.amount() + moved[0];
      int fromSlots = from.slots() + (int) moved[1];
      if (!baseline && (fromAmount != total.amount() || fromSlots != total.slots())) {
        changes.add(new Object[] {worldId, snapshotId, id, fromAmount, total.amount(), fromSlots,
            total.slots(), at});
      }
      if (previous == null) {
        inserts.add(new Object[] {worldId, id, total.amount(), total.slots(), total.containers(), at, at});
      } else if (previous.amount() != total.amount() || previous.slots() != total.slots()) {
        updates.add(new Object[] {total.amount(), total.slots(), total.containers(), at, worldId, id});
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

  private static void add(Map<Integer, long[]> quiet, int id, Held held, int sign) {
    long[] sum = quiet.computeIfAbsent(id, key -> new long[2]);
    sum[0] += sign * held.amount();
    sum[1] += sign * held.slots();
  }
}
