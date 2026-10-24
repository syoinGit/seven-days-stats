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
 * <p>The save does not record who placed or opened a container, so the site decides from what it
 * sees. A storage container becomes a player chest for good when it appears after tracking
 * began (someone placed it) or when its contents change (someone used it). It stops being one
 * when it disappears. Only player chests are stored and totalled; merchants' stock, graves and
 * dropped items never are.
 *
 * <p>Item changes describe what the players did: a placed chest logs its contents as added, but
 * a world chest that becomes a player chest joins the totals without a change, so looting a
 * dungeon chest does not show its leftovers as added.
 */
@Service
@RequiredArgsConstructor
public class CoreKeeperWorldStore {
  private final WorldSnapshotRecorder recorder;
  private final JdbcTemplate jdbc;

  public record Stored(Long snapshotId, int progressEvents, int itemChanges) { }

  /** Stored totals per item: amount sum, filled slots and holding containers. */
  record ItemTotal(long amount, int slots, int containers) { }

  /** Player chests in this save; {@code joined} are world chests that just became one. */
  record Tracked(List<Container> chests, List<Container> joined) { }

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
    Tracked tracked = trackContainers(worldId, world.containers(), now);
    replaceContainers(worldId, snapshotId, tracked.chests());
    int itemChanges = updateItemTotals(worldId, snapshotId, tracked, baseline, now);
    return Optional.of(new Stored(snapshotId, recorded.get().events().size(), itemChanges));
  }

  /**
   * Updates the tracking rows and returns this save's player chests. On the first sighting of a
   * world nothing is a player chest. Two containers on one tile cannot be told apart between
   * saves, so they are left as they are.
   */
  private Tracked trackContainers(Long worldId, List<Container> containers, OffsetDateTime now) {
    Map<Key, List<Container>> byKey = new HashMap<>();
    containers.stream().filter(container -> "STORAGE".equals(container.holder()))
        .forEach(container -> byKey.computeIfAbsent(Key.of(container), key -> new ArrayList<>()).add(container));
    Map<Key, String> firstHashes = new HashMap<>();
    Set<Key> playerChests = new HashSet<>();
    jdbc.query("SELECT object_id, pos_x, pos_z, first_content_hash, player_chest FROM T_CK_CONTAINER_TRACK "
        + "WHERE game_world_id = ?", rs -> {
          Key key = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3));
          firstHashes.put(key, rs.getString(4));
          if (rs.getBoolean(5)) playerChests.add(key);
        }, worldId);
    boolean firstSighting = firstHashes.isEmpty();
    Timestamp at = Timestamp.from(now.toInstant());
    Set<Key> gone = new HashSet<>(firstHashes.keySet());
    gone.removeAll(byKey.keySet());
    List<Object[]> inserts = new ArrayList<>();
    List<Object[]> turnedOn = new ArrayList<>();
    List<Container> chests = new ArrayList<>();
    List<Container> joined = new ArrayList<>();
    byKey.forEach((key, found) -> {
      if (found.size() != 1) return;
      Container container = found.get(0);
      String hash = contentHash(container);
      String first = firstHashes.get(key);
      if (first == null) {
        inserts.add(new Object[] {worldId, key.objectId(), key.x(), key.z(), hash, at, !firstSighting,
            firstSighting ? null : at});
        if (!firstSighting) chests.add(container);
      } else if (playerChests.contains(key)) {
        chests.add(container);
      } else if (!first.equals(hash)) {
        turnedOn.add(new Object[] {at, worldId, key.objectId(), key.x(), key.z()});
        chests.add(container);
        joined.add(container);
      }
    });
    jdbc.batchUpdate("INSERT INTO T_CK_CONTAINER_TRACK (game_world_id, object_id, pos_x, pos_z, "
        + "first_content_hash, first_seen_at, player_chest, player_chest_since) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        inserts);
    jdbc.batchUpdate("UPDATE T_CK_CONTAINER_TRACK SET player_chest = TRUE, player_chest_since = ? "
        + "WHERE game_world_id = ? AND object_id = ? AND pos_x = ? AND pos_z = ?", turnedOn);
    jdbc.batchUpdate("DELETE FROM T_CK_CONTAINER_TRACK WHERE game_world_id = ? AND object_id = ? "
        + "AND pos_x = ? AND pos_z = ?", gone.stream()
            .map(key -> new Object[] {worldId, key.objectId(), key.x(), key.z()}).toList());
    chests.sort(java.util.Comparator.comparingInt(Container::x).thenComparingInt(Container::z)
        .thenComparingInt(Container::objectId));
    return new Tracked(chests, joined);
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
        .usingColumns("game_world_id", "world_snapshot_id", "holder", "object_id", "variation",
            "pos_x", "pos_z", "slot_count", "filled_slots");
    List<Object[]> items = new ArrayList<>();
    for (Container container : containers) {
      Map<String, Object> row = new HashMap<>();
      row.put("game_world_id", worldId);
      row.put("world_snapshot_id", snapshotId);
      row.put("holder", "STORAGE");
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

  private int updateItemTotals(Long worldId, Long snapshotId, Tracked tracked, boolean baseline,
      OffsetDateTime now) {
    Map<Integer, ItemTotal> totals = itemTotals(tracked.chests());
    Map<Integer, ItemTotal> joined = itemTotals(tracked.joined());
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
      ItemTotal before = previous == null ? none : previous;
      // Contents of world chests that just joined count as already there.
      ItemTotal quiet = joined.getOrDefault(id, none);
      long fromAmount = before.amount() + quiet.amount();
      int fromSlots = before.slots() + quiet.slots();
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
}
