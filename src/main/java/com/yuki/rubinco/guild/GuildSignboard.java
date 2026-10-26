package com.yuki.rubinco.guild;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Mire, the girl painted on the tavern's sign, talks with the visitor once a day (guild/signboard.yml). The script is
 * fixed and the visitor picks from given choices; code only picks the day's opener and three topics by date and fills
 * in today's public facts (how many visited, the newest resident, tonight's dungeon run, how many live where).
 * A line whose fact is missing is left out. Nothing is stored: the page keeps "talked today" in the browser.
 */
@Component
public class GuildSignboard {
  public static final Set<String> FACES = Set.of("normal", "smile", "joy", "think", "surprise", "shy", "sad");
  static final Set<String> CONDITIONS = Set.of("busy", "empty", "newest", "run-none", "run-out", "run-back");
  static final Set<String> FACTS = Set.of("visitors", "residents", "newest", "run", "party");
  static final int TOPICS_PER_DAY = 3;
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

  public record Line(String face, String text) { }

  record Said(String face, String text, String when) { }

  public record Choice(String label, String next) { }

  public record Node(List<Line> lines, List<Choice> choices, String next) { }

  record Script(List<Said> lines, List<Choice> choices, String next) { }

  public record Topic(String key, String label, String start) { }

  /** Today's facts; newest, run and party are null when there is none. counts: living residents by village, faction or faith name. */
  public record Facts(int visitors, int residents, String newest, String run, String party, String runState, Map<String, Integer> counts) { }

  /** One day's talk, ready for the page. */
  public record Talk(String date, String name, String role, List<Line> opener, List<Line> menu, List<Topic> topics,
                     Map<String, Node> nodes, List<Line> farewell, List<Line> done) { }

  private final JdbcTemplate jdbc;
  private final GuildService guild;
  private final GuildWorld world;
  private final GuildDungeon dungeon;
  private final String version;
  private final String name;
  private final String role;
  private final List<List<Said>> openers;
  private final List<Said> menu;
  private final List<Said> farewell;
  private final List<Said> done;
  private final List<Topic> topics;
  private final Map<String, Script> nodes;

  public GuildSignboard(JdbcTemplate jdbc, GuildService guild, GuildWorld world, GuildDungeon dungeon) {
    this.jdbc = jdbc;
    this.guild = guild;
    this.world = world;
    this.dungeon = dungeon;
    try (InputStream in = new ClassPathResource("guild/signboard.yml").getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      version = text(root, "version");
      name = text(root, "name");
      role = text(root, "role");
      openers = list(root.get("openers")).stream().map(GuildSignboard::lines).toList();
      menu = lines(root.get("menu"));
      farewell = lines(root.get("farewell"));
      done = lines(root.get("done"));
      topics = list(root.get("topics")).stream().map(GuildSignboard::map)
          .map(m -> new Topic(text(m, "key"), text(m, "label"), text(m, "start"))).toList();
      Map<String, Script> loaded = new LinkedHashMap<>();
      map(root.get("nodes")).forEach((key, value) -> {
        var m = map(value);
        List<Choice> choices = m.get("choices") == null ? List.of()
            : list(m.get("choices")).stream().map(GuildSignboard::map).map(c -> new Choice(text(c, "label"), text(c, "next"))).toList();
        loaded.put(key, new Script(lines(m.get("lines")), choices, m.get("next") == null ? null : String.valueOf(m.get("next"))));
      });
      nodes = Map.copyOf(loaded);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    check();
  }

  String version() {
    return version;
  }

  List<Topic> topics() {
    return topics;
  }

  Map<String, Script> nodes() {
    return nodes;
  }

  /** Today's talk with today's facts. */
  public Talk today(ZonedDateTime now) {
    LocalDate date = now.withZoneSameInstant(GuildService.TOKYO).toLocalDate();
    return talk(date, facts(now));
  }

  /** The same facts the tavern shows: who is in the room now, tonight's run. */
  Facts facts(ZonedDateTime now) {
    var room = guild.room(now);
    LocalDate date = room.date();
    int visitors = room.visits().size();
    int residents = jdbc.queryForObject("SELECT COUNT(*) FROM M_GUILD_CHARACTER WHERE status <> 'DEAD'", Integer.class);
    String newest = jdbc.query("SELECT name FROM M_GUILD_CHARACTER WHERE status <> 'DEAD' ORDER BY born_on DESC, id DESC LIMIT 1",
        rs -> rs.next() ? rs.getString(1) : null);
    Map<String, Integer> counts = new HashMap<>();
    world.counts().forEach(count -> counts.put(count.name(), count.count()));
    var run = dungeon.day(date, now);
    return new Facts(visitors, residents, newest, run == null ? null : run.status(), run == null ? null : run.party(),
        run == null ? "run-none" : run.back() ? "run-back" : "run-out", counts);
  }

  Talk talk(LocalDate date, Facts facts) {
    var opener = openers.get((int) Math.floorMod(mix(date, "opener"), (long) openers.size()));
    var picked = topics.stream().sorted(Comparator.comparingLong(topic -> mix(date, topic.key()))).limit(TOPICS_PER_DAY).toList();
    // Only the nodes today's topics reach.
    Map<String, Node> today = new LinkedHashMap<>();
    var queue = new ArrayDeque<String>();
    picked.forEach(topic -> queue.add(topic.start()));
    while (!queue.isEmpty()) {
      String key = queue.poll();
      if (today.containsKey(key)) continue;
      Script node = nodes.get(key);
      today.put(key, new Node(fill(node.lines(), facts), node.choices(), node.next()));
      node.choices().forEach(choice -> queue.add(choice.next()));
      if (node.next() != null && nodes.containsKey(node.next())) queue.add(node.next());
    }
    return new Talk(date.toString(), name, role, fill(opener, facts), fill(menu, facts), picked, today, fill(farewell, facts), fill(done, facts));
  }

  private static List<Line> fill(List<Said> lines, Facts facts) {
    List<Line> out = new ArrayList<>();
    for (Said said : lines) {
      if (said.when() != null && !holds(said.when(), facts)) continue;
      Matcher m = PLACEHOLDER.matcher(said.text());
      StringBuilder text = new StringBuilder();
      boolean missing = false;
      while (m.find()) {
        String value = value(m.group(1), facts);
        if (value == null) {
          missing = true;
          break;
        }
        m.appendReplacement(text, Matcher.quoteReplacement(value));
      }
      if (missing) continue;
      m.appendTail(text);
      out.add(new Line(said.face(), text.toString()));
    }
    return out;
  }

  private static boolean holds(String when, Facts facts) {
    return switch (when) {
      case "busy" -> facts.visitors() > 0;
      case "empty" -> facts.visitors() == 0;
      case "newest" -> facts.newest() != null;
      default -> when.equals(facts.runState());
    };
  }

  private static String value(String key, Facts facts) {
    if (key.startsWith("count:")) {
      Integer count = facts.counts().get(key.substring(6));
      return count == null ? null : String.valueOf(count);
    }
    return switch (key) {
      case "visitors" -> String.valueOf(facts.visitors());
      case "residents" -> String.valueOf(facts.residents());
      case "newest" -> facts.newest();
      case "run" -> facts.run();
      case "party" -> facts.party();
      default -> null;
    };
  }

  /** The script must hang together: known faces, conditions and facts, choices that lead somewhere, every node reachable. */
  private void check() {
    if (openers.isEmpty() || menu.isEmpty() || farewell.isEmpty() || done.isEmpty() || topics.size() < TOPICS_PER_DAY) {
      throw new IllegalStateException("signboard.yml is missing a part");
    }
    List<Said> all = new ArrayList<>(menu);
    openers.forEach(all::addAll);
    all.addAll(farewell);
    all.addAll(done);
    nodes.values().forEach(node -> all.addAll(node.lines()));
    for (Said said : all) {
      if (!FACES.contains(said.face())) throw new IllegalStateException("Unknown face: " + said.face());
      if (said.when() != null && !CONDITIONS.contains(said.when())) throw new IllegalStateException("Unknown condition: " + said.when());
      Matcher m = PLACEHOLDER.matcher(said.text());
      while (m.find()) {
        if (!FACTS.contains(m.group(1)) && !m.group(1).matches("count:.+")) throw new IllegalStateException("Unknown fact: " + m.group(1));
      }
    }
    Set<String> reached = new HashSet<>();
    var queue = new ArrayDeque<String>();
    topics.forEach(topic -> queue.add(topic.start()));
    while (!queue.isEmpty()) {
      String key = queue.poll();
      Script node = nodes.get(key);
      if (node == null) throw new IllegalStateException("No such node: " + key);
      if (!reached.add(key)) continue;
      if (node.lines().stream().noneMatch(said -> said.when() == null)) throw new IllegalStateException("Node says nothing for sure: " + key);
      if (node.choices().isEmpty() == (node.next() == null)) throw new IllegalStateException("Node needs choices or next: " + key);
      node.choices().forEach(choice -> queue.add(choice.next()));
      if (node.next() != null && !node.next().equals("menu")) queue.add(node.next());
    }
    if (!reached.equals(nodes.keySet())) throw new IllegalStateException("Unreachable nodes in signboard.yml");
  }

  private static long mix(LocalDate date, String salt) {
    long x = date.toEpochDay() * 0x9E3779B97F4A7C15L ^ salt.hashCode() * 0xC2B2AE3D27D4EB4FL;
    x ^= x >>> 31;
    x *= 0xBF58476D1CE4E5B9L;
    return x ^ x >>> 29;
  }

  private static List<Said> lines(Object value) {
    List<Said> out = new ArrayList<>();
    for (Object item : list(value)) {
      Map<String, Object> m = new LinkedHashMap<>(map(item));
      Object when = m.remove("if");
      if (m.size() != 1) throw new IllegalStateException("A signboard line is one face and its text");
      var entry = m.entrySet().iterator().next();
      String text = String.valueOf(entry.getValue());
      if (text.isBlank()) throw new IllegalStateException("Empty signboard line");
      out.add(new Said(entry.getKey(), text, when == null ? null : String.valueOf(when)));
    }
    if (out.isEmpty()) throw new IllegalStateException("A signboard part without lines");
    return List.copyOf(out);
  }

  private static List<?> list(Object value) {
    if (!(value instanceof List<?> list)) throw new IllegalStateException("Invalid signboard.yml");
    return list;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Invalid signboard.yml");
    return (Map<String, Object>) map;
  }

  private static String text(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null || String.valueOf(value).isBlank()) throw new IllegalStateException("signboard.yml missing " + key);
    return String.valueOf(value);
  }
}
