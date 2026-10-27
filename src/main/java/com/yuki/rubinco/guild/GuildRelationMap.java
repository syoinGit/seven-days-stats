package com.yuki.rubinco.guild;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The residents' relationship chart (/guild/relations), laid out here so it draws without JS. Two views: everyone
 * with a bond beyond acquaintance (and open crushes) around one circle, grouped by the nation they live in; or one
 * resident in the middle with the ones they are most tied to around them, how each feels about the other, and the
 * bonds among those around them. Read-only, from the same tables as the character page.
 */
@Service
@RequiredArgsConstructor
public class GuildRelationMap {
  /** Both views are drawn on a square of this many units. */
  static final double SIZE = 600;
  /** How many are around the one in the middle. */
  static final int RING = 8;
  /** How many the overview shows at most (those with the most bonds). */
  static final int OVERVIEW = 40;
  /** The bonds the overview draws (an acquaintance is everyone). */
  static final List<String> DRAWN = List.of("spouse", "lover", "close_friend", "friend", "companion", "mentor", "rival");
  private static final List<String> NATIONS = GuildPlaces.TOWNS.stream().map(GuildPlaces.Place::nation).toList();

  private final JdbcTemplate jdbc;
  private final GuildService guild;

  /** A resident on the chart; x, y in chart units. character: only in the centred view (for the figure). */
  public record Node(long id, String name, String village, boolean dead, double x, double y, GuildService.Character character) {
    public String style() {
      return String.format(Locale.ROOT, "left:%.2f%%;top:%.2f%%", x / SIZE * 100, y / SIZE * 100);
    }

    public String nation() {
      return NATIONS.contains(village) ? village : "other";
    }
  }

  /**
   * A line between two residents. kind: a bond label, "crush" (an arrow from the one in love), "warm" or "cold".
   * cx, cy: the control point of a curve, or null for a straight line.
   */
  public record Edge(double x1, double y1, double x2, double y2, Double cx, Double cy, String kind, String chip) {
    /** The SVG path. */
    public String d() {
      return cx == null ? String.format(Locale.ROOT, "M%.1f %.1fL%.1f %.1f", x1, y1, x2, y2)
          : String.format(Locale.ROOT, "M%.1f %.1fQ%.1f %.1f %.1f %.1f", x1, y1, cx, cy, x2, y2);
    }

    public String chipStyle() {
      return String.format(Locale.ROOT, "left:%.2f%%;top:%.2f%%", (x1 + x2) / 2 / SIZE * 100, (y1 + y2) / 2 / SIZE * 100);
    }
  }

  /** What the one in the middle and one around them are to each other: the bond, and how each sees the other. */
  public record Row(Node other, String label, String toward, String back, List<String> threads, String pairPath) { }

  public record Key(String kind, String name) { }

  /** center: null for the overview. rows: only in the centred view. */
  public record View(Node center, List<Node> nodes, List<Edge> edges, List<Row> rows, List<Key> keys) {
    public boolean empty() {
      return nodes.isEmpty();
    }
  }

  private record Person(long id, String name, String village, String status) { }

  private record Feeling(int trust, int affinity, int wariness, boolean smitten) { }

  /** Everyone with a bond beyond acquaintance or an open crush, among the living. */
  public View overview() {
    var people = people();
    Map<Long, Integer> degree = new HashMap<>();
    List<long[]> bonds = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (var bond : bonds()) {
      if (!DRAWN.contains(bond.getValue()) || !alive(people, bond.getKey()[0]) || !alive(people, bond.getKey()[1])) continue;
      bonds.add(bond.getKey());
      labels.add(bond.getValue());
      degree.merge(bond.getKey()[0], 1, Integer::sum);
      degree.merge(bond.getKey()[1], 1, Integer::sum);
    }
    var crushes = crushes().stream().filter(pair -> alive(people, pair[0]) && alive(people, pair[1])).toList();
    crushes.forEach(pair -> { degree.merge(pair[0], 1, Integer::sum); degree.merge(pair[1], 1, Integer::sum); });
    Set<Long> shown = new LinkedHashSet<>(degree.keySet().stream()
        .sorted(Comparator.comparing((Long id) -> -degree.get(id)).thenComparing(id -> id)).limit(OVERVIEW).toList());
    // Around one circle, nation by nation, with a gap between nations.
    List<Person> order = shown.stream().map(people::get)
        .sorted(Comparator.comparing((Person p) -> NATIONS.contains(p.village()) ? NATIONS.indexOf(p.village()) : NATIONS.size()).thenComparing(Person::id))
        .toList();
    int gaps = (int) order.stream().map(Person::village).distinct().count();
    int slots = order.size() + (gaps > 1 ? gaps : 0);
    Map<Long, Node> nodes = new LinkedHashMap<>();
    int slot = 0;
    String village = null;
    for (var person : order) {
      if (gaps > 1 && !person.village().equals(village)) slot++;
      village = person.village();
      double angle = -Math.PI / 2 + 2 * Math.PI * slot++ / Math.max(1, slots);
      nodes.put(person.id(), new Node(person.id(), person.name(), person.village(), false,
          SIZE / 2 + 240 * Math.cos(angle), SIZE / 2 + 240 * Math.sin(angle), null));
    }
    List<Edge> edges = new ArrayList<>();
    for (int i = 0; i < bonds.size(); i++) {
      var a = nodes.get(bonds.get(i)[0]);
      var b = nodes.get(bonds.get(i)[1]);
      if (a != null && b != null) edges.add(edge(a, b, labels.get(i), null, 14));
    }
    for (var pair : crushes) {
      var a = nodes.get(pair[0]);
      var b = nodes.get(pair[1]);
      if (a != null && b != null) edges.add(edge(a, b, "crush", null, 14));
    }
    return new View(null, List.copyOf(nodes.values()), edges, List.of(), keys(edges));
  }

  /** One resident in the middle and the ones they are most tied to around them. Not found when there is no such resident. */
  public View around(long centerId) {
    var character = guild.character(centerId);
    var people = people();
    Map<Long, Feeling[]> feelings = new LinkedHashMap<>();
    jdbc.query("""
        SELECT r.to_id, r.trust, r.affinity, r.wariness, r.smitten, back.trust, back.affinity, back.wariness, back.smitten
        FROM T_GUILD_RELATION r LEFT JOIN T_GUILD_RELATION back ON back.from_id = r.to_id AND back.to_id = r.from_id
        WHERE r.from_id = ?
        """, rs -> {
          Feeling back = rs.getObject(6) == null ? null : new Feeling(rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getBoolean(9));
          feelings.put(rs.getLong(1), new Feeling[] {new Feeling(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getBoolean(5)), back});
        }, centerId);
    Map<Set<Long>, String> labels = new HashMap<>();
    bonds().forEach(bond -> labels.put(Set.of(bond.getKey()[0], bond.getKey()[1]), bond.getValue()));
    Map<Long, List<String>> threads = new HashMap<>();
    jdbc.query("SELECT kind, a_id, b_id FROM T_GUILD_THREAD WHERE status = 'OPEN' AND (a_id = ? OR b_id = ?) ORDER BY id", rs -> {
      long other = rs.getLong(2) == centerId ? rs.getLong(3) : rs.getLong(2);
      String kind = rs.getString(1);
      String name = GuildInteractionService.THREADS.getOrDefault(kind, kind);
      // A crush belongs to the one in love.
      if (kind.equals("crush")) name = rs.getLong(2) == centerId ? "片思いしている" : "片思いされている";
      threads.computeIfAbsent(other, key -> new ArrayList<>()).add(name);
    }, centerId, centerId);
    // The closest, the most hostile and those with something open first.
    List<Long> ring = feelings.keySet().stream().filter(people::containsKey).sorted(Comparator.comparing((Long id) -> {
      var f = feelings.get(id)[0];
      String label = labels.get(Set.of(centerId, id));
      return -((label != null && !label.equals("acquaintance") ? 200 : 0) + (threads.containsKey(id) ? 100 : 0)
          + Math.abs(f.affinity()) + Math.abs(f.trust()) + f.wariness());
    }).thenComparing(id -> id)).limit(RING).toList();
    var self = people.get(centerId);
    Node center = new Node(centerId, self.name(), self.village(), self.status().equals("DEAD"), SIZE / 2, SIZE / 2, character);
    List<Node> nodes = new ArrayList<>();
    List<Edge> edges = new ArrayList<>();
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < ring.size(); i++) {
      long id = ring.get(i);
      var person = people.get(id);
      double angle = -Math.PI / 2 + 2 * Math.PI * i / ring.size();
      nodes.add(new Node(id, person.name(), person.village(), person.status().equals("DEAD"),
          SIZE / 2 + 215 * Math.cos(angle), SIZE / 2 + 215 * Math.sin(angle), guild.character(id)));
    }
    // Bonds among those around first, so the lines to the middle are drawn over them.
    for (int i = 0; i < nodes.size(); i++) {
      for (int j = i + 1; j < nodes.size(); j++) {
        String label = labels.get(Set.of(nodes.get(i).id(), nodes.get(j).id()));
        if (label != null && DRAWN.contains(label)) edges.add(around(nodes.get(i), nodes.get(j), label));
      }
    }
    for (var node : nodes) {
      var f = feelings.get(node.id());
      String label = labels.get(Set.of(centerId, node.id()));
      String bond = label == null ? null : GuildInteractionService.LABELS.getOrDefault(label, label);
      String toward = feeling(f[0]);
      String back = f[1] == null ? "まだ知らない" : feeling(f[1]);
      var open = threads.getOrDefault(node.id(), List.of());
      String kind = label != null && DRAWN.contains(label) ? label
          : open.contains("片思いしている") || open.contains("片思いされている") ? "crush"
          : f[0].affinity() <= -15 || f[1] != null && f[1].affinity() <= -15 ? "cold" : "warm";
      var from = open.contains("片思いされている") ? node : center;
      var to = from == center ? node : center;
      String chip = bond != null && !label.equals("acquaintance") ? bond : kind.equals("crush") ? "片思い" : toward;
      edges.add(edge(from, to, kind, chip, 40));
      rows.add(new Row(node, bond == null ? "—" : bond, toward, back, open,
          "/guild/pairs/" + Math.min(centerId, node.id()) + "-" + Math.max(centerId, node.id())));
    }
    return new View(center, nodes, edges, rows, keys(edges));
  }

  /** How one sees the other, in a word. */
  static String feeling(Feeling f) {
    if (f.smitten() && f.affinity() >= 30) return "惹かれている";
    if (f.wariness() >= 50 && f.affinity() < 10) return "警戒している";
    if (f.affinity() >= 60) return "大好き";
    if (f.affinity() >= 30) return "好き";
    if (f.affinity() <= -40) return "嫌い";
    if (f.affinity() <= -15) return "苦手";
    if (f.trust() >= 30) return "信頼している";
    return f.affinity() >= 10 ? "好意" : "ふつう";
  }

  /**
   * A curve between two around the middle, bowed outwards so it never passes through the one in the middle: its own
   * middle lies a little outside the ring, on the side between the two.
   */
  private static Edge around(Node a, Node b, String kind) {
    double c = SIZE / 2;
    double ax = a.x() - c, ay = a.y() - c, bx = b.x() - c, by = b.y() - c;
    double sx = ax + bx, sy = ay + by;
    // Facing each other: bow out to one side.
    double angle = Math.hypot(sx, sy) < 1 ? Math.atan2(ay, ax) + Math.PI / 2 : Math.atan2(sy, sx);
    double chord = Math.hypot(sx, sy) / 2;
    double reach = 2 * (Math.hypot(ax, ay) + 30) - chord;
    double cx = c + reach * Math.cos(angle);
    double cy = c + reach * Math.sin(angle);
    double gap = 40;
    double ua = Math.max(1, Math.hypot(cx - a.x(), cy - a.y()));
    double ub = Math.max(1, Math.hypot(cx - b.x(), cy - b.y()));
    return new Edge(a.x() + (cx - a.x()) / ua * gap, a.y() + (cy - a.y()) / ua * gap,
        b.x() + (cx - b.x()) / ub * gap, b.y() + (cy - b.y()) / ub * gap, cx, cy, kind, null);
  }

  /** The line from a to b, pulled back from both so the figures and the arrowhead stay clear. */
  private static Edge edge(Node a, Node b, String kind, String chip, double gap) {
    double dx = b.x() - a.x();
    double dy = b.y() - a.y();
    double length = Math.max(1, Math.hypot(dx, dy));
    double ux = dx / length * Math.min(gap, length / 3);
    double uy = dy / length * Math.min(gap, length / 3);
    return new Edge(a.x() + ux, a.y() + uy, b.x() - ux, b.y() - uy, null, null, kind, chip);
  }

  private static List<Key> keys(List<Edge> edges) {
    Map<String, String> names = new LinkedHashMap<>();
    for (String label : DRAWN) names.put(label, GuildInteractionService.LABELS.get(label));
    names.put("crush", "片思い（矢印の先が相手）");
    names.put("warm", "知人・その他");
    names.put("cold", "苦手・嫌い");
    var present = edges.stream().map(Edge::kind).toList();
    return names.entrySet().stream().filter(entry -> present.contains(entry.getKey())).map(entry -> new Key(entry.getKey(), entry.getValue())).toList();
  }

  private Map<Long, Person> people() {
    Map<Long, Person> people = new HashMap<>();
    jdbc.query("SELECT id, name, current_village, status FROM M_GUILD_CHARACTER", rs -> {
      people.put(rs.getLong(1), new Person(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    });
    return people;
  }

  private static boolean alive(Map<Long, Person> people, long id) {
    var person = people.get(id);
    return person != null && person.status().equals("ALIVE");
  }

  /** Every bond: {low, high} and the label. */
  private List<Map.Entry<long[], String>> bonds() {
    return jdbc.query("SELECT low_id, high_id, label FROM T_GUILD_BOND ORDER BY low_id, high_id",
        (rs, row) -> Map.entry(new long[] {rs.getLong(1), rs.getLong(2)}, rs.getString(3)));
  }

  /** Open crushes: {the one in love, the one they love}. */
  private List<long[]> crushes() {
    return jdbc.query("SELECT a_id, b_id FROM T_GUILD_THREAD WHERE kind = 'crush' AND status = 'OPEN' ORDER BY id",
        (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)});
  }
}
