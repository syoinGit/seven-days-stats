package com.yuki.rubinco.luminite.names;

import com.yuki.rubinco.luminite.ingest.TerrariaImportProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Display names generated from the game's own data. The tables are game text, so they live on
 * the server (outside the public repository); without them every lookup falls back to its id.
 */
@Component
public class TerrariaNames {
  private static final Logger log = LoggerFactory.getLogger(TerrariaNames.class);
  private static final Duration RECHECK = Duration.ofMinutes(1);
  private static final List<String> FILES = List.of("items.tsv", "npcs.tsv", "prefixes.tsv", "containers.tsv", "mapobjects.tsv");

  public record Name(String key, String ja, String en) {
    public String label() {
      return ja != null ? ja : en != null ? en : key;
    }
  }

  private record Tables(Map<Integer, Name> items, Map<Integer, TerrariaItemCategory> itemCategories,
      Map<Integer, Name> npcs,
      Map<String, Name> npcsByKey, Map<Integer, Name> prefixes, Map<String, Name> containers,
      Map<String, Name> mapObjects, Map<String, FileTime> stamps) {
    static final Tables EMPTY = new Tables(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  private final Path directory;
  private volatile Tables tables = Tables.EMPTY;
  private volatile Instant checkedAt = Instant.EPOCH;

  public TerrariaNames(TerrariaImportProperties properties) {
    this.directory = properties.getNamesDir();
    reloadIfChanged(true);
  }

  public Name item(int id) {
    Name name = current().items().get(id);
    return name != null ? name : new Name(String.valueOf(id), null, "アイテム #" + id);
  }

  /** OTHER until the names table has a category column. */
  public TerrariaItemCategory itemCategory(int id) {
    return current().itemCategories().getOrDefault(id, TerrariaItemCategory.OTHER);
  }

  public boolean categoriesLoaded() {
    return !current().itemCategories().isEmpty();
  }

  public Name npc(int netId) {
    Name name = current().npcs().get(netId);
    return name != null ? name : new Name(String.valueOf(netId), null, "NPC #" + netId);
  }

  /** Bestiary entries use the NPC's internal name as their persistent id. */
  public Name npc(String key) {
    Name name = current().npcsByKey().get(key);
    return name != null ? name : new Name(key, null, key);
  }

  public String prefix(int id) {
    if (id <= 0) return null;
    Name name = current().prefixes().get(id);
    return name == null ? "接頭辞 #" + id : name.label();
  }

  public String itemWithPrefix(int itemId, int prefixId) {
    String prefix = prefix(prefixId);
    return prefix == null ? item(itemId).label() : prefix + " " + item(itemId).label();
  }

  public String container(Integer tileType, Integer style) {
    if (tileType == null || style == null) return null;
    Name name = current().containers().get(tileType + ":" + style);
    return name == null ? null : name.label();
  }

  public Name mapObject(String key) {
    Name name = current().mapObjects().get(key);
    return name != null ? name : new Name(key, null, key);
  }

  public boolean loaded() {
    return !current().items().isEmpty();
  }

  public int itemCount() {
    return current().items().size();
  }

  public Path directory() {
    return directory;
  }

  /** Item ids whose Japanese, English or internal name contains the query (kana-insensitive). */
  public Set<Integer> itemsMatching(String query) {
    String needle = normalize(query);
    if (needle.isEmpty()) return Set.of();
    return current().items().entrySet().stream()
        .filter(entry -> contains(entry.getValue(), needle))
        .map(Map.Entry::getKey).collect(Collectors.toSet());
  }

  public boolean matches(Name name, String query) {
    String needle = normalize(query);
    return !needle.isEmpty() && contains(name, needle);
  }

  private static boolean contains(Name name, String needle) {
    return contains(name.ja(), needle) || contains(name.en(), needle) || contains(name.key(), needle);
  }

  private static boolean contains(String value, String needle) {
    return value != null && normalize(value).contains(needle);
  }

  /** NFKC, lower case, katakana folded to hiragana, spaces removed. */
  public static String normalize(String value) {
    if (value == null) return "";
    String text = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
    StringBuilder builder = new StringBuilder(text.length());
    text.codePoints().forEach(c -> {
      if (Character.isWhitespace(c)) return;
      builder.appendCodePoint(c >= 0x30A1 && c <= 0x30F6 ? c - 0x60 : c);
    });
    return builder.toString();
  }

  private Tables current() {
    if (Instant.now().isAfter(checkedAt.plus(RECHECK))) reloadIfChanged(false);
    return tables;
  }

  private synchronized void reloadIfChanged(boolean initial) {
    checkedAt = Instant.now();
    if (directory == null) return;
    try {
      Map<String, FileTime> stamps = new HashMap<>();
      for (String file : FILES) {
        Path path = directory.resolve(file);
        if (Files.isRegularFile(path)) stamps.put(file, Files.getLastModifiedTime(path));
      }
      if (stamps.equals(tables.stamps())) return;
      Map<Integer, Name> items = byId(directory.resolve("items.tsv"));
      Map<Integer, TerrariaItemCategory> itemCategories = new HashMap<>();
      for (String[] row : rows(directory.resolve("items.tsv"))) {
        if (row.length > 4 && !row[4].isBlank()) itemCategories.put(Integer.parseInt(row[0]), TerrariaItemCategory.of(row[4]));
      }
      Map<Integer, Name> npcs = byId(directory.resolve("npcs.tsv"));
      Map<String, Name> npcsByKey = new HashMap<>();
      npcs.values().forEach(name -> npcsByKey.putIfAbsent(name.key(), name));
      Map<String, Name> containers = new HashMap<>();
      for (String[] row : rows(directory.resolve("containers.tsv"))) {
        containers.put(row[0] + ":" + row[1], new Name(row[0] + ":" + row[1], blank(row, 2), blank(row, 3)));
      }
      Map<String, Name> mapObjects = new HashMap<>();
      for (String[] row : rows(directory.resolve("mapobjects.tsv"))) {
        mapObjects.put(row[0], new Name(row[0], blank(row, 1), blank(row, 2)));
      }
      tables = new Tables(Map.copyOf(items), Map.copyOf(itemCategories), Map.copyOf(npcs), Map.copyOf(npcsByKey),
          Map.copyOf(byId(directory.resolve("prefixes.tsv"))), Map.copyOf(containers),
          Map.copyOf(mapObjects), Map.copyOf(stamps));
      log.info("Terraria name tables loaded. directory={}, items={}, npcs={}", directory, items.size(), npcs.size());
    } catch (IOException | RuntimeException exception) {
      if (initial) {
        log.warn("Terraria name tables unavailable; ids are shown instead. directory={}, reason={}",
            directory, exception.getMessage());
      } else {
        log.warn("Terraria name tables could not be reloaded; keeping the previous ones. reason={}",
            exception.getMessage());
      }
    }
  }

  private static Map<Integer, Name> byId(Path path) throws IOException {
    Map<Integer, Name> names = new HashMap<>();
    for (String[] row : rows(path)) {
      names.put(Integer.parseInt(row[0]), new Name(row[1], blank(row, 2), blank(row, 3)));
    }
    return names;
  }

  private static List<String[]> rows(Path path) throws IOException {
    if (!Files.isRegularFile(path)) return List.of();
    return Files.readAllLines(path, StandardCharsets.UTF_8).stream().skip(1)
        .filter(line -> !line.isBlank()).map(line -> line.split("\t", -1))
        .filter(row -> row.length >= 2).toList();
  }

  private static String blank(String[] row, int index) {
    return index < row.length && !row[index].isBlank() ? row[index] : null;
  }
}
