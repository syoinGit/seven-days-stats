package com.yuki.rubinco.luminite.ingest;

import com.yuki.rubinco.luminite.ingest.TerrariaWorld.Container;
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
 * Writes a snapshot and the Terraria tables in one transaction, so pages never see contents from
 * one save next to totals from another. The first snapshot of a world is a baseline: it records
 * what exists without logging every item or kill as a change.
 */
@Service
@RequiredArgsConstructor
public class TerrariaWorldStore {
  private final WorldSnapshotRecorder recorder;
  private final JdbcTemplate jdbc;

  public record Stored(Long snapshotId, int progressEvents, int itemChanges, int killChanges) { }

  @Transactional
  /** {@code itemBaseline} also re-baselines item totals, e.g. after the storage rule changed. */
  public Optional<Stored> store(Long worldId, boolean baseline, boolean itemBaseline, TerrariaWorld world,
      WorldSnapshotRecorder.SnapshotInput input) {
    var recorded = recorder.record(worldId, input);
    if (recorded.isEmpty()) return Optional.empty();
    Long snapshotId = recorded.get().snapshot().getId();
    OffsetDateTime now = OffsetDateTime.now();
    replaceContainers(worldId, snapshotId, world);
    int itemChanges = updateItemTotals(worldId, snapshotId, world, baseline || itemBaseline, now);
    int killChanges = updateBestiary(worldId, snapshotId, world, baseline, now);
    replaceSigns(worldId, snapshotId, world, baseline, now);
    return Optional.of(new Stored(snapshotId, recorded.get().events().size(), itemChanges, killChanges));
  }

  private void replaceContainers(Long worldId, Long snapshotId, TerrariaWorld world) {
    var managed = TerrariaStorage.managed(world);
    jdbc.update("DELETE FROM T_TERRARIA_CONTAINER_ITEM WHERE game_world_id = ?", worldId);
    jdbc.update("DELETE FROM T_TERRARIA_CONTAINER WHERE game_world_id = ?", worldId);
    SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbc).withTableName("T_TERRARIA_CONTAINER")
        .usingGeneratedKeyColumns("terraria_container_id")
        .usingColumns("game_world_id", "world_snapshot_id", "container_kind", "tile_type",
            "tile_style", "tile_x", "tile_y", "name", "slot_count", "filled_slots", "managed");
    List<Object[]> items = new ArrayList<>();
    for (Container container : world.containers()) {
      Map<String, Object> row = new HashMap<>();
      row.put("game_world_id", worldId);
      row.put("world_snapshot_id", snapshotId);
      row.put("container_kind", container.kind().name());
      row.put("tile_type", container.tileType());
      row.put("tile_style", container.tileStyle());
      row.put("tile_x", container.x());
      row.put("tile_y", container.y());
      row.put("name", container.name() == null || container.name().isBlank() ? null
          : truncate(container.name(), 100));
      row.put("slot_count", container.slots());
      row.put("filled_slots", container.items().size());
      row.put("managed", managed.test(container));
      long containerId = insert.executeAndReturnKey(row).longValue();
      container.items().forEach(item -> items.add(new Object[] {
          containerId, worldId, item.slot(), item.itemId(), item.stack(), item.prefixId()}));
    }
    jdbc.batchUpdate("INSERT INTO T_TERRARIA_CONTAINER_ITEM "
        + "(terraria_container_id, game_world_id, slot, item_id, stack, prefix_id) VALUES (?, ?, ?, ?, ?, ?)",
        items);
  }

  private int updateItemTotals(Long worldId, Long snapshotId, TerrariaWorld world, boolean baseline,
      OffsetDateTime now) {
    Map<Integer, Long> totals = TerrariaSummary.itemTotals(world);
    Map<Integer, Set<Container>> holders = new HashMap<>();
    world.containers().stream().filter(TerrariaStorage.managed(world)).forEach(container -> container.items().forEach(item ->
        holders.computeIfAbsent(item.itemId(), id -> new HashSet<>()).add(container)));
    Map<Integer, long[]> existing = new HashMap<>();
    jdbc.query("SELECT item_id, total, container_count FROM T_TERRARIA_ITEM_TOTAL WHERE game_world_id = ?",
        rs -> {
          existing.put(rs.getInt(1), new long[] {rs.getLong(2), rs.getLong(3)});
        }, worldId);
    Timestamp at = Timestamp.from(now.toInstant());
    List<Object[]> inserts = new ArrayList<>();
    List<Object[]> updates = new ArrayList<>();
    List<Object[]> changes = new ArrayList<>();
    Set<Integer> ids = new TreeSet<>(totals.keySet());
    ids.addAll(existing.keySet());
    for (int id : ids) {
      long total = totals.getOrDefault(id, 0L);
      int holderCount = holders.getOrDefault(id, Set.of()).size();
      long[] previous = existing.get(id);
      if (previous == null) {
        inserts.add(new Object[] {worldId, id, total, holderCount, at, at});
        if (!baseline) changes.add(new Object[] {worldId, snapshotId, id, 0L, total, at});
      } else if (previous[0] != total) {
        updates.add(new Object[] {total, holderCount, at, worldId, id});
        if (!baseline) changes.add(new Object[] {worldId, snapshotId, id, previous[0], total, at});
      } else if (previous[1] != holderCount) {
        jdbc.update("UPDATE T_TERRARIA_ITEM_TOTAL SET container_count = ? WHERE game_world_id = ? AND item_id = ?",
            holderCount, worldId, id);
      }
    }
    jdbc.batchUpdate("INSERT INTO T_TERRARIA_ITEM_TOTAL "
        + "(game_world_id, item_id, total, container_count, first_seen_at, last_changed_at) VALUES (?, ?, ?, ?, ?, ?)",
        inserts);
    jdbc.batchUpdate("UPDATE T_TERRARIA_ITEM_TOTAL SET total = ?, container_count = ?, last_changed_at = ? "
        + "WHERE game_world_id = ? AND item_id = ?", updates);
    jdbc.batchUpdate("INSERT INTO T_TERRARIA_ITEM_CHANGE "
        + "(game_world_id, world_snapshot_id, item_id, previous_total, new_total, observed_at) VALUES (?, ?, ?, ?, ?, ?)",
        changes);
    return changes.size();
  }

  private int updateBestiary(Long worldId, Long snapshotId, TerrariaWorld world, boolean baseline,
      OffsetDateTime now) {
    Map<String, Object[]> existing = new HashMap<>();
    jdbc.query("SELECT npc_key, kills, sighted, chatted FROM T_TERRARIA_BESTIARY WHERE game_world_id = ?",
        rs -> {
          existing.put(rs.getString(1), new Object[] {rs.getInt(2), rs.getBoolean(3), rs.getBoolean(4)});
        }, worldId);
    Set<String> keys = new TreeSet<>(world.bestiaryKills().keySet());
    keys.addAll(world.bestiarySighted());
    keys.addAll(world.bestiaryChatted());
    Timestamp at = Timestamp.from(now.toInstant());
    List<Object[]> inserts = new ArrayList<>();
    List<Object[]> updates = new ArrayList<>();
    List<Object[]> changes = new ArrayList<>();
    for (String key : keys) {
      if (key.length() > 80) continue;
      int kills = world.bestiaryKills().getOrDefault(key, 0);
      boolean sighted = world.bestiarySighted().contains(key);
      boolean chatted = world.bestiaryChatted().contains(key);
      Object[] previous = existing.get(key);
      if (previous == null) {
        inserts.add(new Object[] {worldId, key, kills, sighted, chatted, at, at});
        if (!baseline && kills > 0) changes.add(new Object[] {worldId, snapshotId, key, 0, kills, at});
      } else if ((int) previous[0] != kills || (boolean) previous[1] != sighted
          || (boolean) previous[2] != chatted) {
        updates.add(new Object[] {kills, sighted, chatted, at, worldId, key});
        if (!baseline && (int) previous[0] != kills) {
          changes.add(new Object[] {worldId, snapshotId, key, previous[0], kills, at});
        }
      }
    }
    // The tracker never forgets an entry; a vanished key means a restored backup and is left as is.
    jdbc.batchUpdate("INSERT INTO T_TERRARIA_BESTIARY "
        + "(game_world_id, npc_key, kills, sighted, chatted, first_seen_at, last_changed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        inserts);
    jdbc.batchUpdate("UPDATE T_TERRARIA_BESTIARY SET kills = ?, sighted = ?, chatted = ?, last_changed_at = ? "
        + "WHERE game_world_id = ? AND npc_key = ?", updates);
    jdbc.batchUpdate("INSERT INTO T_TERRARIA_KILL_CHANGE "
        + "(game_world_id, world_snapshot_id, npc_key, previous_kills, new_kills, observed_at) VALUES (?, ?, ?, ?, ?, ?)",
        changes);
    return changes.size();
  }

  /** Keeps each sign's first-seen time across saves; baseline signs keep NULL. */
  private void replaceSigns(Long worldId, Long snapshotId, TerrariaWorld world, boolean baseline,
      OffsetDateTime now) {
    Map<String, Timestamp> seen = new HashMap<>();
    Set<String> known = new HashSet<>();
    jdbc.query("SELECT tile_x, tile_y, text, first_seen_at FROM T_TERRARIA_SIGN WHERE game_world_id = ?",
        rs -> {
          String key = rs.getInt(1) + ":" + rs.getInt(2) + ":" + rs.getString(3);
          known.add(key);
          seen.put(key, rs.getTimestamp(4));
        }, worldId);
    jdbc.update("DELETE FROM T_TERRARIA_SIGN WHERE game_world_id = ?", worldId);
    Timestamp at = Timestamp.from(now.toInstant());
    List<Object[]> rows = new ArrayList<>();
    for (TerrariaWorld.Sign sign : world.signs()) {
      String text = truncate(sign.text(), 4000);
      String key = sign.x() + ":" + sign.y() + ":" + text;
      Timestamp firstSeen = known.contains(key) ? seen.get(key) : baseline ? null : at;
      rows.add(new Object[] {worldId, snapshotId, sign.tileType(), sign.x(), sign.y(), text, firstSeen});
    }
    jdbc.batchUpdate("INSERT INTO T_TERRARIA_SIGN "
        + "(game_world_id, world_snapshot_id, tile_type, tile_x, tile_y, text, first_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        rows);
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
