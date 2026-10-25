package com.yuki.rubinco.octarine.names;

import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.octarine.ingest.CoreKeeperImportProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Object names generated from the game's ObjectID list. The table is derived from game files, so
 * it lives on the server (outside the public repository); without it every object shows its id.
 */
@Component
public class CoreKeeperNames {
  private static final Logger log = LoggerFactory.getLogger(CoreKeeperNames.class);
  private static final Duration RECHECK = Duration.ofMinutes(1);
  private static final String FILE = "objects.tsv";

  /**
   * {@code key} is the game's internal name. {@code type} and {@code tags} are the game's
   * ObjectType and ObjectCategoryTag numbers; null and empty when the table has no such columns.
   */
  public record Name(int id, String key, String ja, String en, Integer type, Set<Integer> tags) {
    public String label() {
      return ja != null ? ja : en != null ? en : key != null ? key : "#" + id;
    }

    public ItemCategory category() {
      return ItemCategory.of(type, tags);
    }

    /** Bosses are progress: their names stay off public statistics. */
    public boolean boss() {
      return key != null && key.contains("Boss");
    }
  }

  private record Table(Map<Integer, Name> objects, FileTime stamp) {
    static final Table EMPTY = new Table(Map.of(), null);
  }

  private final Path directory;
  private volatile Table table = Table.EMPTY;
  private volatile Instant checkedAt = Instant.EPOCH;

  public CoreKeeperNames(CoreKeeperImportProperties properties) {
    this.directory = properties.getNamesDir();
    reloadIfChanged(true);
  }

  public Name object(int id) {
    Name name = current().objects().get(id);
    return name != null ? name : new Name(id, null, null, null, null, Set.of());
  }

  public boolean loaded() {
    return !current().objects().isEmpty();
  }

  public int count() {
    return current().objects().size();
  }

  public Path directory() {
    return directory;
  }

  /** Ids whose Japanese, English or internal name contains the query (kana-insensitive). */
  public Set<Integer> matching(String query) {
    String needle = TerrariaNames.normalize(query);
    if (needle.isEmpty()) return Set.of();
    return current().objects().values().stream()
        .filter(name -> contains(name.ja(), needle) || contains(name.en(), needle)
            || contains(name.key(), needle))
        .map(Name::id).collect(Collectors.toSet());
  }

  private static boolean contains(String value, String needle) {
    return value != null && TerrariaNames.normalize(value).contains(needle);
  }

  private Table current() {
    if (Instant.now().isAfter(checkedAt.plus(RECHECK))) reloadIfChanged(false);
    return table;
  }

  private synchronized void reloadIfChanged(boolean initial) {
    checkedAt = Instant.now();
    if (directory == null) return;
    Path path = directory.resolve(FILE);
    try {
      if (!Files.isRegularFile(path)) return;
      FileTime stamp = Files.getLastModifiedTime(path);
      if (stamp.equals(table.stamp())) return;
      Map<Integer, Name> objects = new HashMap<>();
      Files.readAllLines(path, StandardCharsets.UTF_8).stream().skip(1)
          .filter(line -> !line.isBlank()).map(line -> line.split("\t", -1))
          .filter(row -> row.length >= 2).forEach(row -> {
            int id = Integer.parseInt(row[0]);
            String type = blank(row, 4);
            String tags = blank(row, 5);
            objects.put(id, new Name(id, blank(row, 1), blank(row, 2), blank(row, 3),
                type == null ? null : Integer.valueOf(type),
                tags == null ? Set.of() : java.util.Arrays.stream(tags.split(","))
                    .map(String::strip).map(Integer::valueOf).collect(Collectors.toUnmodifiableSet())));
          });
      table = new Table(Map.copyOf(objects), stamp);
      log.info("Core Keeper object names loaded. directory={}, objects={}", directory, objects.size());
    } catch (IOException | RuntimeException exception) {
      log.warn("Core Keeper object names {}. directory={}, reason={}",
          initial ? "unavailable; ids are shown instead" : "could not be reloaded; keeping the previous ones",
          directory, exception.getMessage());
    }
  }

  private static String blank(String[] row, int index) {
    return index < row.length && !row[index].isBlank() ? row[index] : null;
  }
}
