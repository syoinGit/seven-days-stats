package com.yuki.rubinco.luminite.service;

import com.yuki.rubinco.luminite.ingest.TerrariaCatalog;
import com.yuki.rubinco.luminite.ingest.TerrariaImportService;
import com.yuki.rubinco.luminite.ingest.TerrariaSummary;
import com.yuki.rubinco.luminite.names.TerrariaNames;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Read models for the Luminite pages. Everything comes from the latest valid snapshot. */
@Service
@RequiredArgsConstructor
public class LuminiteQueryService {
  public static final String GAME_ID = TerrariaImportService.GAME_ID;
  private static final DisplayTimeFormatter TIME = new DisplayTimeFormatter();
  private static final int TOMBSTONE = 85;

  private final JdbcTemplate jdbc;
  private final M_GameWorldRepository worlds;
  private final T_WorldSnapshotRepository snapshots;
  private final ObjectMapper objectMapper;
  private final TerrariaNames names;

  /** The active world as the viewer may see it; empty summary until the first import. */
  public record Context(M_GameWorld world, T_WorldSnapshot snapshot, TerrariaSummary summary,
      String snapshotLabel) {
    public boolean hasData() {
      return summary != null;
    }

    public Long worldId() {
      return world.getId();
    }
  }

  /** In-game GPS style position: east/west of the world centre and depth below the surface. */
  public record Place(int x, int y, String horizontal, String depth, String layer) {
    public String label() {
      return horizontal + " / " + depth;
    }
  }

  public record Stat(String label, String value, String note) { }

  public record WorldView(String name, String seed, String size, String difficulty, String evil,
      boolean hardMode, String created, List<String> worldTypes, String dimensions,
      List<String> oreChoices, int altarsSmashed, Place spawn, Place dungeon) { }

  public record EventsView(String clock, String moon, List<String> active, String invasion,
      String anglerQuest, List<String> anglersFinishedToday) { }

  public record ActivityItem(OffsetDateTime at, String atLabel, String kind, String title,
      List<String> details, String link) { }

  public record ItemRow(int itemId, String name, String englishName, long total, int containers,
      String firstSeen, String lastChanged) { }

  public record Holder(Long containerId, String container, String kind, Place place, int slot,
      int stack, String prefix) { }

  public record ItemChange(String at, long previous, long current, long delta) { }

  public record ItemDetail(ItemRow item, List<Holder> holders, List<ItemChange> changes) { }

  public record ContainerItem(int slot, int itemId, String name, int stack, boolean match) { }

  public record ContainerRow(Long id, String label, String kind, String customName, Place place,
      int slots, int filled, List<ContainerItem> items) { }

  public record BestiaryRow(String key, String name, String englishName, int kills, boolean sighted,
      boolean chatted, String lastChanged) { }

  public record ResourceRow(String group, String name, long count) { }

  public record NpcRow(String name, String type, boolean homeless, Place home) { }

  public record PylonRow(String name, Place place) { }

  public record SignRow(String text, Place place, String firstSeen, boolean tombstone) { }

  @Transactional(readOnly = true)
  public Optional<Context> context(boolean admin) {
    M_GameWorld world = worlds.findFirstByGameIdAndActiveTrue(GAME_ID).orElse(null);
    if (world == null || (!world.isPublished() && !admin)) return Optional.empty();
    T_WorldSnapshot snapshot = world.getLatestSnapshotId() == null ? null
        : snapshots.findById(world.getLatestSnapshotId()).orElse(null);
    TerrariaSummary summary = null;
    if (snapshot != null) {
      try {
        summary = objectMapper.readValue(snapshot.getExtractedJson(), TerrariaSummary.class);
      } catch (RuntimeException exception) {
        summary = null;
      }
    }
    return Optional.of(new Context(world, snapshot, summary,
        snapshot == null ? null : TIME.format(snapshot.getSourceModifiedAt())));
  }

  /** Whether the world is configured but hidden from members (shown as "準備中"). */
  public boolean hiddenWorld(boolean admin) {
    return !admin && worlds.findFirstByGameIdAndActiveTrue(GAME_ID).map(world -> !world.isPublished())
        .orElse(false);
  }

  public Place place(TerrariaSummary summary, int x, int y) {
    var world = summary.world();
    int east = 2 * x + 1 - world.tilesWide();
    String horizontal = east == 0 ? "中心" : (east > 0 ? "中心から東へ" : "中心から西へ") + Math.abs(east) + "ft";
    int depth = (int) Math.round(2 * y - 2 * world.groundLevel());
    String vertical = depth == 0 ? "地表" : depth > 0 ? "地表から" + depth + "ft下" : "地表から" + (-depth) + "ft上";
    String layer;
    if (y < world.groundLevel() * 0.35) layer = "空";
    else if (y < world.groundLevel()) layer = "地上";
    else if (y < world.rockLevel()) layer = "地下";
    else if (y < world.tilesHigh() - 200) layer = "洞窟";
    else layer = "地獄";
    return new Place(x, y, horizontal, vertical, layer);
  }

  public WorldView world(TerrariaSummary summary) {
    var w = summary.world();
    List<String> ores = new ArrayList<>();
    // Pre-hardmode tiers first, then the hardmode ones chosen when altars were smashed.
    List.of("copper", "iron", "silver", "gold", "cobalt", "mythril", "adamantite").forEach(tier -> {
      Integer tile = w.oreTiers().get(tier);
      TerrariaCatalog.RESOURCES.stream().filter(resource -> tile != null && resource.tileId() == tile)
          .findFirst().ifPresent(resource -> ores.add(names.item(resource.itemId()).label()));
    });
    return new WorldView(w.title(), w.seed(), sizeLabel(w.tilesWide(), w.tilesHigh()),
        difficulty(w.gameMode()), w.crimson() ? "クリムゾン" : "コラプション", w.hardMode(),
        w.createdAt() == null ? null : TIME.format(w.createdAt().atOffset(ZoneOffset.UTC)),
        w.worldTypes(), w.tilesWide() + " × " + w.tilesHigh(), ores, w.altarsSmashed(), place(summary, w.spawnX(), w.spawnY()),
        place(summary, w.dungeonX(), w.dungeonY()));
  }

  public EventsView events(TerrariaSummary summary) {
    var e = summary.events();
    var c = summary.clock();
    List<String> active = new ArrayList<>();
    if (e.bloodMoon()) active.add("ブラッドムーン");
    if (e.eclipse()) active.add("にっしょく");
    if (e.slimeRain()) active.add("スライムのあめ");
    if (e.sandstorm()) active.add("すなあらし");
    if (e.party()) active.add("パーティー");
    if (e.lanternNight()) active.add("ランタンナイト");
    if (c != null && c.raining()) active.add("あめ");
    String invasion = switch (e.invasionType()) {
      case 0 -> null;
      case 1 -> "ゴブリンしんりゃく";
      case 2 -> "フロストぐんだん";
      case 3 -> "かいぞくのしゅうらい";
      case 4 -> "かせいじんの狂気";
      default -> "侵略イベント #" + e.invasionType();
    };
    if (invasion != null && e.invasionSizeStart() > 0) {
      invasion += "（残り " + e.invasionSize() + " / " + e.invasionSizeStart() + "）";
    }
    return new EventsView(c == null ? null : clock(c.time(), c.dayTime()),
        c == null ? null : moon(c.moonPhase()), active, invasion,
        e.anglerQuestItemId() > 0 ? names.item(e.anglerQuestItemId()).label() : null,
        e.anglersFinishedToday());
  }

  /** Boss count is achieved-only; the total is never shown so progress stays a surprise. */
  public List<Stat> highlights(Context context, long bossesDone) {
    var totals = context.summary().totals();
    return List.of(
        new Stat("倒したボス", String.valueOf(bossesDone), "保存データで討伐を確認できたボス"),
        new Stat("保管アイテム", totals.distinctItems() + "種",
            String.format("合計 %,d 個（管理チェスト）", totals.storedItems())),
        new Stat("討伐数", String.format("%,d", totals.bestiaryKills()),
            totals.bestiaryKilledSpecies() + "種類の敵（図鑑の記録）"),
        new Stat("墓石", String.valueOf(totals.tombstones()), "ワールドに残っている墓石"),
        new Stat("住人", String.valueOf(context.summary().townNpcs().size()), "保存時点でワールドにいたNPC"),
        new Stat("パイロン", String.valueOf(context.summary().pylons().size()), "設置済みのテレポートパイロン"));
  }

  /** Item, kill and tombstone changes grouped per snapshot, newest first. */
  @Transactional(readOnly = true)
  public List<ActivityItem> activity(Context context, int limit) {
    Long worldId = context.worldId();
    List<ActivityItem> result = new ArrayList<>();
    Map<Long, List<Object[]>> itemChanges = new LinkedHashMap<>();
    jdbc.query("SELECT world_snapshot_id, item_id, previous_total, new_total, observed_at FROM T_TERRARIA_ITEM_CHANGE "
        + "WHERE game_world_id = ? ORDER BY observed_at DESC, terraria_item_change_id DESC LIMIT 500", rs -> {
          itemChanges.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(new Object[] {
              rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getTimestamp(5)});
        }, worldId);
    itemChanges.values().forEach(rows -> {
      List<String> up = new ArrayList<>();
      List<String> down = new ArrayList<>();
      rows.stream().sorted(Comparator.comparingLong((Object[] row) -> -Math.abs((long) row[2] - (long) row[1])))
          .forEach(row -> {
            long delta = (long) row[2] - (long) row[1];
            String text = names.item((int) row[0]).label() + (delta > 0 ? " +" : " ") + delta;
            (delta > 0 ? up : down).add(text);
          });
      List<String> details = new ArrayList<>();
      if (!up.isEmpty()) details.add("増えた: " + String.join("、", up.subList(0, Math.min(8, up.size())))
          + (up.size() > 8 ? " ほか" + (up.size() - 8) + "件" : ""));
      if (!down.isEmpty()) details.add("減った: " + String.join("、", down.subList(0, Math.min(8, down.size())))
          + (down.size() > 8 ? " ほか" + (down.size() - 8) + "件" : ""));
      OffsetDateTime at = time((Timestamp) rows.get(0)[3]);
      result.add(new ActivityItem(at, TIME.format(at), "ITEMS", "保管アイテムの変化（" + rows.size() + "種）",
          details, null));
    });
    Map<Long, List<Object[]>> killChanges = new LinkedHashMap<>();
    jdbc.query("SELECT world_snapshot_id, npc_key, previous_kills, new_kills, observed_at FROM T_TERRARIA_KILL_CHANGE "
        + "WHERE game_world_id = ? ORDER BY observed_at DESC, terraria_kill_change_id DESC LIMIT 500", rs -> {
          killChanges.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(new Object[] {
              rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getTimestamp(5)});
        }, worldId);
    killChanges.values().forEach(rows -> {
      int total = rows.stream().mapToInt(row -> (int) row[2] - (int) row[1]).sum();
      List<String> parts = rows.stream()
          .sorted(Comparator.comparingInt((Object[] row) -> (int) row[1] - (int) row[2]))
          .limit(8).map(row -> names.npc((String) row[0]).label() + " +" + ((int) row[2] - (int) row[1]))
          .toList();
      OffsetDateTime at = time((Timestamp) rows.get(0)[3]);
      result.add(new ActivityItem(at, TIME.format(at), "KILLS", "討伐 +" + total,
          List.of(String.join("、", parts) + (rows.size() > 8 ? " ほか" + (rows.size() - 8) + "種" : "")), null));
    });
    jdbc.query("SELECT text, first_seen_at FROM T_TERRARIA_SIGN WHERE game_world_id = ? AND tile_type = ? "
        + "AND first_seen_at IS NOT NULL ORDER BY first_seen_at DESC LIMIT 20", rs -> {
          OffsetDateTime at = time(rs.getTimestamp(2));
          result.add(new ActivityItem(at, TIME.format(at), "DEATH", "新しい墓石",
              List.of(rs.getString(1).replace('\n', ' ')), null));
        }, worldId, TOMBSTONE);
    return result.stream().sorted(Comparator.comparing(ActivityItem::at).reversed()).limit(limit).toList();
  }

  @Transactional(readOnly = true)
  public Page<ItemRow> items(Context context, String query, String sort, int page, int size) {
    List<ItemRow> rows = jdbc.query("SELECT item_id, total, container_count, first_seen_at, last_changed_at "
        + "FROM T_TERRARIA_ITEM_TOTAL WHERE game_world_id = ? AND total > 0",
        (rs, i) -> itemRow(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getTimestamp(4), rs.getTimestamp(5)),
        context.worldId());
    Set<Integer> matching = blank(query) ? null : names.itemsMatching(query);
    Comparator<ItemRow> order = switch (sort == null ? "" : sort) {
      case "name" -> Comparator.comparing(ItemRow::name);
      case "recent" -> Comparator.comparing(ItemRow::lastChanged, Comparator.nullsLast(Comparator.reverseOrder()));
      case "id" -> Comparator.comparingInt(ItemRow::itemId);
      default -> Comparator.comparingLong(ItemRow::total).reversed().thenComparing(ItemRow::name);
    };
    List<ItemRow> filtered = rows.stream()
        .filter(row -> matching == null || matching.contains(row.itemId())
            || String.valueOf(row.itemId()).equals(query.strip()))
        .sorted(order).toList();
    return page(filtered, page, size);
  }

  @Transactional(readOnly = true)
  public Optional<ItemDetail> item(Context context, int itemId) {
    List<ItemRow> rows = jdbc.query("SELECT item_id, total, container_count, first_seen_at, last_changed_at "
        + "FROM T_TERRARIA_ITEM_TOTAL WHERE game_world_id = ? AND item_id = ?",
        (rs, i) -> itemRow(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getTimestamp(4), rs.getTimestamp(5)),
        context.worldId(), itemId);
    if (rows.isEmpty()) return Optional.empty();
    List<Holder> holders = jdbc.query("SELECT c.terraria_container_id, c.container_kind, c.tile_type, c.tile_style, "
        + "c.name, c.tile_x, c.tile_y, i.slot, i.stack, i.prefix_id FROM T_TERRARIA_CONTAINER_ITEM i "
        + "JOIN T_TERRARIA_CONTAINER c ON c.terraria_container_id = i.terraria_container_id "
        + "WHERE i.game_world_id = ? AND i.item_id = ? AND c.managed ORDER BY i.stack DESC, c.terraria_container_id, i.slot",
        (rs, i) -> new Holder(rs.getLong(1),
            containerLabel(rs.getString(2), (Integer) rs.getObject(3), (Integer) rs.getObject(4), rs.getString(5)),
            kindLabel(rs.getString(2)), place(context.summary(), rs.getInt(6), rs.getInt(7)), rs.getInt(8),
            rs.getInt(9), names.prefix(rs.getInt(10))),
        context.worldId(), itemId);
    List<ItemChange> changes = jdbc.query("SELECT observed_at, previous_total, new_total FROM T_TERRARIA_ITEM_CHANGE "
        + "WHERE game_world_id = ? AND item_id = ? ORDER BY observed_at DESC, terraria_item_change_id DESC LIMIT 100",
        (rs, i) -> new ItemChange(TIME.format(time(rs.getTimestamp(1))), rs.getLong(2), rs.getLong(3),
            rs.getLong(3) - rs.getLong(2)),
        context.worldId(), itemId);
    return Optional.of(new ItemDetail(rows.get(0), holders, changes));
  }

  @Transactional(readOnly = true)
  public Page<ContainerRow> containers(Context context, String query, String kind, int page, int size) {
    Map<Long, List<ContainerItem>> items = new LinkedHashMap<>();
    Set<Integer> matching = blank(query) ? Set.of() : names.itemsMatching(query);
    jdbc.query("SELECT i.terraria_container_id, i.slot, i.item_id, i.stack FROM T_TERRARIA_CONTAINER_ITEM i "
        + "JOIN T_TERRARIA_CONTAINER c ON c.terraria_container_id = i.terraria_container_id "
        + "WHERE i.game_world_id = ? AND c.managed ORDER BY i.terraria_container_id, i.slot", rs -> {
          int itemId = rs.getInt(3);
          items.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(new ContainerItem(rs.getInt(2),
              itemId, names.item(itemId).label(), rs.getInt(4), matching.contains(itemId)));
        }, context.worldId());
    String needle = TerrariaNames.normalize(query);
    List<ContainerRow> rows = jdbc.query("SELECT terraria_container_id, container_kind, tile_type, tile_style, name, "
        + "tile_x, tile_y, slot_count, filled_slots FROM T_TERRARIA_CONTAINER WHERE game_world_id = ? AND managed "
        + "ORDER BY tile_x, tile_y", (rs, i) -> containerRow(context, rs.getLong(1), rs.getString(2),
            (Integer) rs.getObject(3), (Integer) rs.getObject(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
            rs.getInt(8), rs.getInt(9), items.getOrDefault(rs.getLong(1), List.of())), context.worldId());
    List<ContainerRow> filtered = rows.stream()
        .filter(row -> blank(kind) || row.kind().equals(kindLabel(kind)))
        .filter(row -> needle.isEmpty() || row.items().stream().anyMatch(ContainerItem::match)
            || TerrariaNames.normalize(row.label()).contains(needle))
        .filter(row -> !blank(query) || !blank(kind) || row.filled() > 0)
        .toList();
    return page(filtered, page, size);
  }

  @Transactional(readOnly = true)
  public Optional<ContainerRow> container(Context context, long containerId) {
    List<ContainerItem> items = jdbc.query("SELECT slot, item_id, stack, prefix_id FROM T_TERRARIA_CONTAINER_ITEM "
        + "WHERE game_world_id = ? AND terraria_container_id = ? ORDER BY slot",
        (rs, i) -> new ContainerItem(rs.getInt(1), rs.getInt(2), names.itemWithPrefix(rs.getInt(2), rs.getInt(4)),
            rs.getInt(3), false), context.worldId(), containerId);
    return jdbc.query("SELECT terraria_container_id, container_kind, tile_type, tile_style, name, tile_x, tile_y, "
        + "slot_count, filled_slots FROM T_TERRARIA_CONTAINER WHERE game_world_id = ? AND terraria_container_id = ? "
        + "AND managed",
        (rs, i) -> containerRow(context, rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3),
            (Integer) rs.getObject(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), items),
        context.worldId(), containerId).stream().findFirst();
  }

  public List<String> containerKinds() {
    return List.of("CHEST", "DRESSER", "ITEM_FRAME", "WEAPON_RACK", "MANNEQUIN", "HAT_RACK", "FOOD_PLATTER",
        "DISPLAY_JAR");
  }

  @Transactional(readOnly = true)
  public List<BestiaryRow> bestiary(Context context, String query, String sort) {
    List<BestiaryRow> rows = jdbc.query("SELECT npc_key, kills, sighted, chatted, last_changed_at FROM T_TERRARIA_BESTIARY "
        + "WHERE game_world_id = ?", (rs, i) -> new BestiaryRow(rs.getString(1), names.npc(rs.getString(1)).label(),
            names.npc(rs.getString(1)).en(), rs.getInt(2), rs.getBoolean(3), rs.getBoolean(4), TIME.format(time(rs.getTimestamp(5)))),
        context.worldId());
    Comparator<BestiaryRow> order = "name".equals(sort) ? Comparator.comparing(BestiaryRow::name)
        : Comparator.comparingInt(BestiaryRow::kills).reversed().thenComparing(BestiaryRow::name);
    return rows.stream()
        .filter(row -> blank(query) || names.matches(names.npc(row.key()), query))
        .sorted(order).toList();
  }

  public List<ResourceRow> resources(TerrariaSummary summary, String group) {
    return TerrariaCatalog.RESOURCES.stream().filter(resource -> resource.group().equals(group))
        .map(resource -> new ResourceRow(group, names.item(resource.itemId()).label(),
            summary.tiles().resources().getOrDefault(resource.tileId(), 0L)))
        .toList();
  }

  public List<ResourceRow> objects(TerrariaSummary summary) {
    return TerrariaCatalog.OBJECTS.stream()
        .map(object -> new ResourceRow("OBJECT", object.mapObjectKey() != null
            ? names.mapObject(object.mapObjectKey()).label() : names.item(object.itemId()).label(),
            summary.tiles().objects().getOrDefault(object.key(), 0)))
        .toList();
  }

  public List<NpcRow> townNpcs(TerrariaSummary summary) {
    return summary.townNpcs().stream()
        .map(npc -> new NpcRow(npc.name(), names.npc(npc.netId()).label(), npc.homeless(),
            npc.homeless() ? null : place(summary, npc.homeX(), npc.homeY())))
        .sorted(Comparator.comparing(NpcRow::type)).toList();
  }

  public List<PylonRow> pylons(TerrariaSummary summary) {
    return summary.pylons().stream().map(pylon -> {
      Integer item = TerrariaCatalog.pylonItem(pylon.style());
      return new PylonRow(item == null ? "パイロン" : names.item(item).label(), place(summary, pylon.x(), pylon.y()));
    }).toList();
  }

  @Transactional(readOnly = true)
  public List<SignRow> signs(Context context, boolean tombstones) {
    String condition = tombstones ? "tile_type = ?" : "(tile_type IS NULL OR tile_type <> ?)";
    return jdbc.query("SELECT text, tile_x, tile_y, first_seen_at FROM T_TERRARIA_SIGN WHERE game_world_id = ? AND "
        + condition + " ORDER BY first_seen_at DESC NULLS LAST, terraria_sign_id", (rs, i) -> new SignRow(
            rs.getString(1), place(context.summary(), rs.getInt(2), rs.getInt(3)),
            rs.getTimestamp(4) == null ? null : TIME.format(time(rs.getTimestamp(4))), tombstones),
        context.worldId(), TOMBSTONE);
  }

  public String itemName(int itemId) {
    return names.item(itemId).label();
  }

  public boolean namesLoaded() {
    return names.loaded();
  }

  private ContainerRow containerRow(Context context, Long id, String kind, Integer tileType, Integer style,
      String name, int x, int y, int slots, int filled, List<ContainerItem> items) {
    return new ContainerRow(id, containerLabel(kind, tileType, style, name), kindLabel(kind), name,
        place(context.summary(), x, y), slots, filled, items);
  }

  private String containerLabel(String kind, Integer tileType, Integer style, String name) {
    String type = names.container(tileType, style);
    String base = type != null ? type : kindLabel(kind);
    return name == null || name.isBlank() ? base : name + "（" + base + "）";
  }

  /** Kind names use the placing item's in-game name when the tables are present. */
  public String kindLabel(String kind) {
    Integer item = switch (kind) {
      case "CHEST" -> 48;
      case "DRESSER" -> 334;
      case "ITEM_FRAME" -> 3270;
      case "WEAPON_RACK" -> 2699;
      case "MANNEQUIN" -> 498;
      case "HAT_RACK" -> 3977;
      case "FOOD_PLATTER" -> 4326;
      case "DISPLAY_JAR" -> 5472;
      default -> null;
    };
    var name = item == null ? null : names.item(item);
    if (name != null && name.ja() != null) return name.ja();
    return switch (kind) {
      case "CHEST" -> "チェスト";
      case "DRESSER" -> "タンス";
      case "ITEM_FRAME" -> "アイテムフレーム";
      case "WEAPON_RACK" -> "武器棚";
      case "MANNEQUIN" -> "マネキン";
      case "HAT_RACK" -> "帽子掛け";
      case "FOOD_PLATTER" -> "プレート";
      case "DISPLAY_JAR" -> "アイテムフラスコ";
      default -> kind;
    };
  }

  private ItemRow itemRow(int itemId, long total, int containers, Timestamp firstSeen, Timestamp lastChanged) {
    var name = names.item(itemId);
    return new ItemRow(itemId, name.label(), name.en(), total, containers,
        TIME.format(time(firstSeen)), TIME.format(time(lastChanged)));
  }

  private static String sizeLabel(int wide, int high) {
    if (wide == 4200 && high == 1200) return "小";
    if (wide == 6400 && high == 1800) return "中";
    if (wide == 8400 && high == 2400) return "大";
    return "カスタム";
  }

  private static String difficulty(int gameMode) {
    return switch (gameMode) {
      case 0 -> "クラシック";
      case 1 -> "エキスパート";
      case 2 -> "マスター";
      case 3 -> "ジャーニー";
      default -> "不明 (" + gameMode + ")";
    };
  }

  /** Game time counts seconds from 4:30 (day) or 19:30 (night). */
  static String clock(double time, boolean day) {
    int minutes = (int) (time / 60.0) + (day ? 4 * 60 + 30 : 19 * 60 + 30);
    minutes %= 24 * 60;
    return String.format("%d:%02d", minutes / 60, minutes % 60);
  }

  private static String moon(int phase) {
    return switch (phase) {
      case 0 -> "満月";
      case 1 -> "十八夜ごろ";
      case 2 -> "下弦の月";
      case 3 -> "有明月";
      case 4 -> "新月";
      case 5 -> "三日月";
      case 6 -> "上弦の月";
      case 7 -> "十三夜ごろ";
      default -> null;
    };
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

  /** Item ids grouped by progress-relevant helpers for templates. */
  public Map<String, List<ResourceRow>> resourceGroups(TerrariaSummary summary) {
    return List.of("ORE", "GEM", "BLOCK").stream()
        .collect(Collectors.toMap(group -> group, group -> resources(summary, group), (a, b) -> a, LinkedHashMap::new));
  }
}
