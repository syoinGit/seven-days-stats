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

/** Read models for the Octarine data pages. Everything comes from the latest valid snapshot. */
@Service
@RequiredArgsConstructor
public class OctarineQueryService {
  public static final String GAME_ID = CoreKeeperImportService.GAME_ID;
  private static final DisplayTimeFormatter TIME = new DisplayTimeFormatter();
  public static final List<String> CATEGORIES =
      List.of("CREATURE", "CROP", "CONTAINER", "BREAKABLE", "DIG_SPOT", "OBJECT");

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

  public record Summary(long containers, long distinctItems, long storedSlots, long entities,
      long creatures, long crops, long skippedEntities) { }

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

  public record Holder(Long containerId, String container, String holder, Place place, int slot,
      int amount) { }

  public record ItemChange(String at, long previous, long current, long delta, int previousSlots,
      int currentSlots) { }

  public record ItemDetail(ItemRow item, List<Holder> holders, List<ItemChange> changes) { }

  public record ContainerItem(int slot, int objectId, String name, int amount, boolean match) { }

  public record ContainerRow(Long id, String label, String holder, Place place, int slots,
      int filled, List<ContainerItem> items) { }

  public record CountRow(String name, String key, long count) { }

  public record CropRow(String name, long total, Map<Integer, Long> stages) { }

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
          summary = new Summary(json.path("containers").asLong(), json.path("distinctItems").asLong(),
              json.path("storedSlots").asLong(), json.path("entities").asLong(),
              json.path("creatures").asLong(), json.path("crops").asLong(),
              json.path("skippedEntities").asLong());
        }
      } catch (RuntimeException exception) {
        summary = null;
      }
    }
    return Optional.of(new Context(world, snapshot, summary,
        snapshot == null || snapshot.getSourceModifiedAt() == null ? null
            : TIME.format(snapshot.getSourceModifiedAt())));
  }

  public List<Stat> highlights(Context context, long achieved) {
    Summary s = context.summary();
    return List.of(
        new Stat("達成した進捗", String.valueOf(achieved), "保存データで達成を確認できた項目"),
        new Stat("保管アイテム", s.distinctItems() + "種", String.format("%,d 枠（チェスト・ツボ・台など）", s.storedSlots())),
        new Stat("入れ物", String.format("%,d", s.containers()), "中身を持てる物の数（商人を除く）"),
        new Stat("生き物", String.format("%,d", s.creatures()), "保存時点で記録されていた敵や動物"),
        new Stat("作物", String.format("%,d", s.crops()), "育っている植物"));
  }

  /** Item changes grouped per snapshot, newest first. */
  @Transactional(readOnly = true)
  public List<ActivityItem> activity(Context context, int limit) {
    Map<Long, List<Object[]>> bySnapshot = new LinkedHashMap<>();
    jdbc.query("SELECT world_snapshot_id, object_id, previous_slots, new_slots, previous_amount, new_amount, "
        + "observed_at FROM T_CK_ITEM_CHANGE WHERE game_world_id = ? "
        + "ORDER BY observed_at DESC, ck_item_change_id DESC LIMIT 500", rs -> {
          bySnapshot.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(new Object[] {
              rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5), rs.getLong(6), rs.getTimestamp(7)});
        }, context.worldId());
    List<ActivityItem> result = new ArrayList<>();
    bySnapshot.values().forEach(rows -> {
      List<String> up = new ArrayList<>();
      List<String> down = new ArrayList<>();
      rows.stream().sorted(Comparator.comparingLong((Object[] row) -> -Math.abs((long) row[4] - (long) row[3])))
          .forEach(row -> {
            long delta = (long) row[4] - (long) row[3];
            String name = names.object((int) row[0]).label();
            if (delta != 0) (delta > 0 ? up : down).add(name + (delta > 0 ? " +" : " ") + delta);
            else (((int) row[2]) > ((int) row[1]) ? up : down).add(name);
          });
      List<String> details = new ArrayList<>();
      if (!up.isEmpty()) details.add("増えた: " + joinLimited(up));
      if (!down.isEmpty()) details.add("減った: " + joinLimited(down));
      OffsetDateTime at = time((Timestamp) rows.get(0)[5]);
      result.add(new ActivityItem(at, TIME.format(at), "保管アイテムの変化（" + rows.size() + "種）", details));
    });
    return result.stream().limit(limit).toList();
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
    List<Holder> holders = jdbc.query("SELECT c.ck_container_id, c.object_id, c.holder, c.pos_x, c.pos_z, "
        + "i.slot, i.amount FROM T_CK_CONTAINER_ITEM i JOIN T_CK_CONTAINER c ON c.ck_container_id = i.ck_container_id "
        + "WHERE i.game_world_id = ? AND i.object_id = ? ORDER BY c.holder DESC, i.amount DESC, c.ck_container_id, i.slot",
        (rs, i) -> new Holder(rs.getLong(1), names.object(rs.getInt(2)).label(), holderLabel(rs.getString(3)),
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

  /** {@code holder} is null (storage), STORAGE or NPC. Without a query, empty ones are hidden. */
  @Transactional(readOnly = true)
  public Page<ContainerRow> containers(Context context, String query, String holder, int page, int size) {
    Set<Integer> matching = blank(query) ? Set.of() : names.matching(query);
    Map<Long, List<ContainerItem>> items = new LinkedHashMap<>();
    jdbc.query("SELECT ck_container_id, slot, object_id, amount FROM T_CK_CONTAINER_ITEM "
        + "WHERE game_world_id = ? ORDER BY ck_container_id, slot", rs -> {
          int objectId = rs.getInt(3);
          items.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(new ContainerItem(rs.getInt(2),
              objectId, names.object(objectId).label(), rs.getInt(4), matching.contains(objectId)));
        }, context.worldId());
    String needle = TerrariaNames.normalize(query);
    String wanted = holder == null ? "STORAGE" : holder;
    List<ContainerRow> rows = jdbc.query("SELECT ck_container_id, object_id, holder, pos_x, pos_z, slot_count, "
        + "filled_slots FROM T_CK_CONTAINER WHERE game_world_id = ? AND holder = ? ORDER BY pos_x, pos_z, ck_container_id",
        (rs, i) -> containerRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
            rs.getInt(6), rs.getInt(7), items.getOrDefault(rs.getLong(1), List.of())),
        context.worldId(), wanted);
    return page(rows.stream()
        .filter(row -> needle.isEmpty() || row.items().stream().anyMatch(ContainerItem::match)
            || TerrariaNames.normalize(row.label()).contains(needle))
        .filter(row -> !blank(query) || row.filled() > 0)
        .toList(), page, size);
  }

  @Transactional(readOnly = true)
  public Optional<ContainerRow> container(Context context, long containerId) {
    List<ContainerItem> items = jdbc.query("SELECT slot, object_id, amount FROM T_CK_CONTAINER_ITEM "
        + "WHERE game_world_id = ? AND ck_container_id = ? ORDER BY slot",
        (rs, i) -> new ContainerItem(rs.getInt(1), rs.getInt(2), names.object(rs.getInt(2)).label(),
            rs.getInt(3), false), context.worldId(), containerId);
    return jdbc.query("SELECT ck_container_id, object_id, holder, pos_x, pos_z, slot_count, filled_slots "
        + "FROM T_CK_CONTAINER WHERE game_world_id = ? AND ck_container_id = ?",
        (rs, i) -> containerRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
            rs.getInt(6), rs.getInt(7), items), context.worldId(), containerId).stream().findFirst();
  }

  /**
   * Entity counts per category, keyed by the category's display label. Bosses and boss parts are left out: they belong to progress,
   * which is only shown once achieved. Without the name table nothing can be recognised as a
   * boss, so objects are then listed by id only.
   */
  @Transactional(readOnly = true)
  public Map<String, List<CountRow>> objectCounts(Context context) {
    Map<String, List<CountRow>> result = new LinkedHashMap<>();
    CATEGORIES.forEach(category -> result.put(category, new ArrayList<>()));
    jdbc.query("SELECT object_id, category, entity_count FROM T_CK_OBJECT_COUNT WHERE game_world_id = ? "
        + "ORDER BY entity_count DESC, object_id", rs -> {
          var name = names.object(rs.getInt(1));
          List<CountRow> rows = result.get(rs.getString(2));
          if (rows == null || name.boss()) return;
          rows.add(new CountRow(name.label(), name.key(), rs.getLong(3)));
        }, context.worldId());
    Map<String, List<CountRow>> labelled = new LinkedHashMap<>();
    result.forEach((category, rows) -> labelled.put(categoryLabel(category), rows));
    return labelled;
  }

  @Transactional(readOnly = true)
  public List<CropRow> crops(Context context) {
    Map<Integer, Map<Integer, Long>> byPlant = new LinkedHashMap<>();
    jdbc.query("SELECT object_id, stage, entity_count FROM T_CK_CROP_COUNT WHERE game_world_id = ? "
        + "ORDER BY object_id, stage", rs -> {
          byPlant.computeIfAbsent(rs.getInt(1), id -> new java.util.TreeMap<>()).put(rs.getInt(2), rs.getLong(3));
        }, context.worldId());
    return byPlant.entrySet().stream()
        .map(entry -> new CropRow(names.object(entry.getKey()).label(),
            entry.getValue().values().stream().mapToLong(Long::longValue).sum(), entry.getValue()))
        .sorted(Comparator.comparingLong(CropRow::total).reversed().thenComparing(CropRow::name))
        .toList();
  }

  public boolean namesLoaded() {
    return names.loaded();
  }

  public static String categoryLabel(String category) {
    return switch (category) {
      case "CREATURE" -> "生き物（敵・動物）";
      case "CROP" -> "作物";
      case "CONTAINER" -> "入れ物";
      case "BREAKABLE" -> "壊せる物（ツボ・木箱など）";
      case "DIG_SPOT" -> "掘れる場所";
      default -> "その他の設置物";
    };
  }

  private static String holderLabel(String holder) {
    return "NPC".equals(holder) ? "商人など" : "保管";
  }

  private ContainerRow containerRow(Long id, int objectId, String holder, int x, int z, int slots,
      int filled, List<ContainerItem> items) {
    return new ContainerRow(id, names.object(objectId).label(), holderLabel(holder), new Place(x, z),
        slots, filled, items);
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
