package com.yuki.rubinco.octarine.service;

import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.octarine.ingest.CoreKeeperImportService;
import com.yuki.rubinco.octarine.names.CoreKeeperNames;
import com.yuki.rubinco.shared.DisplayTimeFormatter;
import com.yuki.rubinco.worldprogress.entity.M_GameWorld;
import com.yuki.rubinco.worldprogress.entity.T_WorldSnapshot;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldSnapshotRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read models for the Octarine data pages. Everything comes from the latest valid snapshot and
 * covers only player chests, the ones labelled in game (see {@code CoreKeeperWorldStore}).
 * Labels are player-written text; templates escape them like any other game string.
 */
@Service
@RequiredArgsConstructor
public class OctarineQueryService {
  public static final String GAME_ID = CoreKeeperImportService.GAME_ID;
  private static final DisplayTimeFormatter TIME = new DisplayTimeFormatter();

  private final JdbcTemplate jdbc;
  private final M_GameWorldRepository worlds;
  private final T_WorldSnapshotRepository snapshots;
  private final ObjectMapper objectMapper;
  private final CoreKeeperNames names;

  /** The active world as the viewer may see it; no summary until the first import. */
  public record Context(M_GameWorld world, T_WorldSnapshot snapshot, Summary summary,
      String snapshotLabel) {
    public boolean hasData() {
      return summary != null;
    }

    public Long worldId() {
      return world.getId();
    }
  }

  /** Administrator diagnostics from the snapshot: every storage container, touched or not. */
  public record Summary(long containers, long skippedEntities) { }

  /** Tiles from the Core: +x east, +z north. */
  public record Place(int x, int z) {
    public String label() {
      if (x == 0 && z == 0) return "コア";
      List<String> parts = new ArrayList<>();
      if (x != 0) parts.add((x > 0 ? "東" : "西") + Math.abs(x));
      if (z != 0) parts.add((z > 0 ? "北" : "南") + Math.abs(z));
      return "コアから" + String.join("・", parts);
    }
  }

  public record Stat(String label, String value, String note) { }

  public record ActivityItem(OffsetDateTime at, String atLabel, String title, List<String> details) { }

  public record ItemRow(int objectId, String name, String key, long amount, int slots,
      int containers, String lastChanged) { }

  public record Holder(Long containerId, String chest, String kind, Place place, int slot, int amount) { }

  public record ItemChange(String at, long previous, long current, long delta, int previousSlots,
      int currentSlots) { }

  public record ItemDetail(ItemRow item, List<Holder> holders, List<ItemChange> changes) { }

  public record ContainerItem(int slot, int objectId, String name, int amount, boolean match) { }

  /** {@code name} is the label written in game; {@code kind} the chest type. */
  public record ContainerRow(Long id, String name, String kind, Place place, int slots, int filled,
      List<ContainerItem> items) { }

  public record ChestChange(String at, String item, int objectId, long previous, long current,
      long delta) { }

  @Transactional(readOnly = true)
  public Optional<Context> context(boolean admin) {
    M_GameWorld world = worlds.findFirstByGameIdAndActiveTrue(GAME_ID).orElse(null);
    if (world == null || (!world.isPublished() && !admin)) return Optional.empty();
    T_WorldSnapshot snapshot = world.getLatestSnapshotId() == null ? null
        : snapshots.findById(world.getLatestSnapshotId()).orElse(null);
    Summary summary = null;
    if (snapshot != null) {
      try {
        JsonNode json = objectMapper.readTree(snapshot.getExtractedJson()).get("summary");
        // Snapshots taken before chest support have no summary; pages wait for the next import.
        if (json != null && json.isObject()) {
          summary = new Summary(json.path("containers").asLong(), json.path("skippedEntities").asLong());
        }
      } catch (RuntimeException exception) {
        summary = null;
      }
    }
    return Optional.of(new Context(world, snapshot, summary,
        snapshot == null || snapshot.getSourceModifiedAt() == null ? null
            : TIME.format(snapshot.getSourceModifiedAt())));
  }

  @Transactional(readOnly = true)
  public List<Stat> highlights(Context context, long achieved) {
    Long containers = jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_CONTAINER WHERE game_world_id = ?",
        Long.class, context.worldId());
    var items = jdbc.queryForMap("SELECT COUNT(*) AS kinds, COALESCE(SUM(slot_count), 0) AS slots "
        + "FROM T_CK_ITEM_TOTAL WHERE game_world_id = ? AND slot_count > 0", context.worldId());
    return List.of(
        new Stat("達成した進捗", String.valueOf(achieved), "保存データで達成を確認できた項目"),
        new Stat("保管アイテム", ((Number) items.get("kinds")).longValue() + "種",
            String.format("%,d 枠", ((Number) items.get("slots")).longValue())),
        new Stat("プレイヤーのチェスト", String.format("%,d", containers), "ゲーム内でラベルを付けたチェスト"));
  }

  /** Chest changes grouped per snapshot, newest first; one line per chest. */
  @Transactional(readOnly = true)
  public List<ActivityItem> activity(Context context, int limit) {
    Map<Long, Map<String, List<Object[]>>> bySnapshot = new LinkedHashMap<>();
    Map<Long, Timestamp> times = new LinkedHashMap<>();
    jdbc.query("SELECT world_snapshot_id, chest_object_id, pos_x, pos_z, label, object_id, previous_amount, "
        + "new_amount, observed_at FROM T_CK_CHEST_CHANGE WHERE game_world_id = ? "
        + "ORDER BY observed_at DESC, ck_chest_change_id DESC LIMIT 1000", rs -> {
          long snapshot = rs.getLong(1);
          times.putIfAbsent(snapshot, rs.getTimestamp(9));
          String chest = rs.getInt(2) + ":" + rs.getInt(3) + ":" + rs.getInt(4) + ":" + rs.getString(5);
          bySnapshot.computeIfAbsent(snapshot, id -> new LinkedHashMap<>())
              .computeIfAbsent(chest, key -> new ArrayList<>())
              .add(new Object[] {rs.getString(5), rs.getInt(6), rs.getLong(7), rs.getLong(8)});
        }, context.worldId());
    List<ActivityItem> result = new ArrayList<>();
    bySnapshot.forEach((snapshot, chests) -> {
      List<String> details = new ArrayList<>();
      chests.values().forEach(rows -> details.add("「" + rows.get(0)[0] + "」 " + describe(rows)));
      OffsetDateTime at = time(times.get(snapshot));
      result.add(new ActivityItem(at, TIME.format(at), "チェストの変化（" + chests.size() + "個）",
          details.subList(0, Math.min(10, details.size()))));
    });
    return result.stream().limit(limit).toList();
  }

  /** One chest's changes, newest first. */
  @Transactional(readOnly = true)
  public List<ChestChange> chestChanges(Context context, long containerId, int limit) {
    return jdbc.query("SELECT h.observed_at, h.object_id, h.previous_amount, h.new_amount "
        + "FROM T_CK_CHEST_CHANGE h JOIN T_CK_CONTAINER c ON c.game_world_id = h.game_world_id "
        + "AND c.object_id = h.chest_object_id AND c.pos_x = h.pos_x AND c.pos_z = h.pos_z "
        + "WHERE c.game_world_id = ? AND c.ck_container_id = ? "
        + "ORDER BY h.observed_at DESC, h.ck_chest_change_id DESC LIMIT ?",
        (rs, i) -> new ChestChange(TIME.format(time(rs.getTimestamp(1))), names.object(rs.getInt(2)).label(),
            rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getLong(4) - rs.getLong(3)),
        context.worldId(), containerId, limit);
  }

  /** "増えた: A +3 / 減った: B -2", largest changes first. */
  private String describe(List<Object[]> rows) {
    List<String> up = new ArrayList<>();
    List<String> down = new ArrayList<>();
    rows.stream().sorted(Comparator.comparingLong((Object[] row) -> -Math.abs((long) row[3] - (long) row[2])))
        .forEach(row -> {
          long delta = (long) row[3] - (long) row[2];
          (delta > 0 ? up : down).add(names.object((int) row[1]).label() + (delta > 0 ? " +" : " ") + delta);
        });
    List<String> parts = new ArrayList<>();
    if (!up.isEmpty()) parts.add("増えた: " + joinLimited(up));
    if (!down.isEmpty()) parts.add("減った: " + joinLimited(down));
    return String.join(" / ", parts);
  }

  @Transactional(readOnly = true)
  public Page<ItemRow> items(Context context, String query, String sort, int page, int size) {
    List<ItemRow> rows = jdbc.query("SELECT object_id, total_amount, slot_count, container_count, last_changed_at "
        + "FROM T_CK_ITEM_TOTAL WHERE game_world_id = ? AND slot_count > 0",
        (rs, i) -> itemRow(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getTimestamp(5)),
        context.worldId());
    Set<Integer> matching = blank(query) ? null : names.matching(query);
    Comparator<ItemRow> order = switch (sort == null ? "" : sort) {
      case "name" -> Comparator.comparing(ItemRow::name);
      case "recent" -> Comparator.comparing(ItemRow::lastChanged, Comparator.nullsLast(Comparator.reverseOrder()));
      case "slots" -> Comparator.comparingInt(ItemRow::slots).reversed().thenComparing(ItemRow::name);
      default -> Comparator.comparingLong(ItemRow::amount).reversed().thenComparing(ItemRow::name);
    };
    return page(rows.stream()
        .filter(row -> matching == null || matching.contains(row.objectId())
            || String.valueOf(row.objectId()).equals(query.strip()))
        .sorted(order).toList(), page, size);
  }

  /** Only items that have been stored in this world generation have a page. */
  @Transactional(readOnly = true)
  public Optional<ItemDetail> item(Context context, int objectId) {
    List<ItemRow> rows = jdbc.query("SELECT object_id, total_amount, slot_count, container_count, last_changed_at "
        + "FROM T_CK_ITEM_TOTAL WHERE game_world_id = ? AND object_id = ?",
        (rs, i) -> itemRow(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getTimestamp(5)),
        context.worldId(), objectId);
    if (rows.isEmpty()) return Optional.empty();
    List<Holder> holders = jdbc.query("SELECT c.ck_container_id, c.label, c.object_id, c.pos_x, c.pos_z, "
        + "i.slot, i.amount FROM T_CK_CONTAINER_ITEM i JOIN T_CK_CONTAINER c ON c.ck_container_id = i.ck_container_id "
        + "WHERE i.game_world_id = ? AND i.object_id = ? ORDER BY i.amount DESC, c.ck_container_id, i.slot",
        (rs, i) -> new Holder(rs.getLong(1), rs.getString(2), names.object(rs.getInt(3)).label(),
            new Place(rs.getInt(4), rs.getInt(5)), rs.getInt(6), rs.getInt(7)),
        context.worldId(), objectId);
    List<ItemChange> changes = jdbc.query("SELECT observed_at, previous_amount, new_amount, previous_slots, new_slots "
        + "FROM T_CK_ITEM_CHANGE WHERE game_world_id = ? AND object_id = ? "
        + "ORDER BY observed_at DESC, ck_item_change_id DESC LIMIT 100",
        (rs, i) -> new ItemChange(TIME.format(time(rs.getTimestamp(1))), rs.getLong(2), rs.getLong(3),
            rs.getLong(3) - rs.getLong(2), rs.getInt(4), rs.getInt(5)),
        context.worldId(), objectId);
    return Optional.of(new ItemDetail(rows.get(0), holders, changes));
  }

  /** Every player chest, including empty ones: a label means the players care about it. */
  @Transactional(readOnly = true)
  public Page<ContainerRow> containers(Context context, String query, int page, int size) {
    Set<Integer> matching = blank(query) ? Set.of() : names.matching(query);
    Map<Long, List<ContainerItem>> items = new LinkedHashMap<>();
    jdbc.query("SELECT ck_container_id, slot, object_id, amount FROM T_CK_CONTAINER_ITEM "
        + "WHERE game_world_id = ? ORDER BY ck_container_id, slot", rs -> {
          int objectId = rs.getInt(3);
          items.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(new ContainerItem(rs.getInt(2),
              objectId, names.object(objectId).label(), rs.getInt(4), matching.contains(objectId)));
        }, context.worldId());
    String needle = TerrariaNames.normalize(query);
    List<ContainerRow> rows = jdbc.query("SELECT ck_container_id, object_id, pos_x, pos_z, slot_count, "
        + "filled_slots, label FROM T_CK_CONTAINER WHERE game_world_id = ? ORDER BY label, pos_x, pos_z, ck_container_id",
        (rs, i) -> containerRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
            rs.getInt(5), rs.getInt(6), rs.getString(7), items.getOrDefault(rs.getLong(1), List.of())),
        context.worldId());
    return page(rows.stream()
        .filter(row -> needle.isEmpty() || row.items().stream().anyMatch(ContainerItem::match)
            || TerrariaNames.normalize(row.name()).contains(needle)
            || TerrariaNames.normalize(row.kind()).contains(needle))
        .toList(), page, size);
  }

  @Transactional(readOnly = true)
  public Optional<ContainerRow> container(Context context, long containerId) {
    List<ContainerItem> items = jdbc.query("SELECT slot, object_id, amount FROM T_CK_CONTAINER_ITEM "
        + "WHERE game_world_id = ? AND ck_container_id = ? ORDER BY slot",
        (rs, i) -> new ContainerItem(rs.getInt(1), rs.getInt(2), names.object(rs.getInt(2)).label(),
            rs.getInt(3), false), context.worldId(), containerId);
    return jdbc.query("SELECT ck_container_id, object_id, pos_x, pos_z, slot_count, filled_slots, label "
        + "FROM T_CK_CONTAINER WHERE game_world_id = ? AND ck_container_id = ?",
        (rs, i) -> containerRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
            rs.getInt(5), rs.getInt(6), rs.getString(7), items), context.worldId(), containerId)
        .stream().findFirst();
  }

  public boolean namesLoaded() {
    return names.loaded();
  }

  private ContainerRow containerRow(Long id, int objectId, int x, int z, int slots, int filled,
      String label, List<ContainerItem> items) {
    return new ContainerRow(id, label, names.object(objectId).label(), new Place(x, z), slots, filled,
        items);
  }

  private ItemRow itemRow(int objectId, long amount, int slots, int containers, Timestamp lastChanged) {
    var name = names.object(objectId);
    return new ItemRow(objectId, name.label(), name.key(), amount, slots, containers,
        TIME.format(time(lastChanged)));
  }

  private static String joinLimited(List<String> values) {
    return String.join("、", values.subList(0, Math.min(8, values.size())))
        + (values.size() > 8 ? " ほか" + (values.size() - 8) + "件" : "");
  }

  private static OffsetDateTime time(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static <T> Page<T> page(List<T> rows, int page, int size) {
    int safe = Math.max(0, page);
    int from = Math.min(rows.size(), safe * size);
    return new PageImpl<>(rows.subList(from, Math.min(rows.size(), from + size)), PageRequest.of(safe, size),
        rows.size());
  }
}
