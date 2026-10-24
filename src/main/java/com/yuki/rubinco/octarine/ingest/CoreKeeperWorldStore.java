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
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a snapshot and the Core Keeper tables in one transaction, so pages never mix contents
 * from two saves. The first snapshot of a world is a baseline and logs no item changes.
 */
@Service
@RequiredArgsConstructor
public class CoreKeeperWorldStore {
  private final WorldSnapshotRecorder recorder;
  private final JdbcTemplate jdbc;

  public record Stored(Long snapshotId, int progressEvents, int itemChanges) { }

  /** Stored totals per item: amount sum, filled slots and holding containers. */
  record ItemTotal(long amount, int slots, int containers) { }

  @Transactional
  public Optional<Stored> store(Long worldId, boolean baseline, CoreKeeperWorld world,
      WorldSnapshotRecorder.SnapshotInput input) {
    var recorded = recorder.record(worldId, input);
    if (recorded.isEmpty()) return Optional.empty();
    Long snapshotId = recorded.get().snapshot().getId();
    OffsetDateTime now = OffsetDateTime.now();
    replaceContainers(worldId, snapshotId, world.containers());
    int itemChanges = updateItemTotals(worldId, snapshotId, world, baseline, now);
    replaceCounts(worldId, world);
    return Optional.of(new Stored(snapshotId, recorded.get().events().size(), itemChanges));
  }

  /** Totals over STORAGE holders; merchants' stock is not something the players stored. */
  static Map<Integer, ItemTotal> itemTotals(CoreKeeperWorld world) {
    Map<Integer, long[]> sums = new HashMap<>();
    Map<Integer, Set<Container>> holders = new HashMap<>();
    for (Container container : world.containers()) {
      if (!"STORAGE".equals(container.holder())) continue;
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
        .usingColumns("game_world_id", "world_snapshot_id", "holder", "object_id", "variation",
            "pos_x", "pos_z", "slot_count", "filled_slots");
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
      long containerId = insert.executeAndReturnKey(row).longValue();
      container.items().forEach(item -> items.add(new Object[] {
          containerId, worldId, item.slot(), item.objectId(), item.amount(), item.variation()}));
    }
    jdbc.batchUpdate("INSERT INTO T_CK_CONTAINER_ITEM "
        + "(ck_container_id, game_world_id, slot, object_id, amount, variation) VALUES (?, ?, ?, ?, ?, ?)",
        items);
  }

  private int updateItemTotals(Long worldId, Long snapshotId, CoreKeeperWorld world,
      boolean baseline, OffsetDateTime now) {
    Map<Integer, ItemTotal> totals = itemTotals(world);
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

  private void replaceCounts(Long worldId, CoreKeeperWorld world) {
    jdbc.update("DELETE FROM T_CK_OBJECT_COUNT WHERE game_world_id = ?", worldId);
    jdbc.update("DELETE FROM T_CK_CROP_COUNT WHERE game_world_id = ?", worldId);
    jdbc.batchUpdate("INSERT INTO T_CK_OBJECT_COUNT (game_world_id, object_id, category, entity_count) "
        + "VALUES (?, ?, ?, ?)", world.objects().stream().map(tally -> new Object[] {
            worldId, tally.objectId(), tally.category(), tally.count()}).toList());
    jdbc.batchUpdate("INSERT INTO T_CK_CROP_COUNT (game_world_id, object_id, stage, entity_count) "
        + "VALUES (?, ?, ?, ?)", world.crops().stream().map(crop -> new Object[] {
            worldId, crop.objectId(), crop.stage(), crop.count()}).toList());
  }
}
