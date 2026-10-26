package com.yuki.rubinco.guild;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only views of interactions and relationships. Nothing here writes or calls the AI. */
@Service
@RequiredArgsConstructor
public class GuildChronicle {
  static final int PAGE = 30;
  private final JdbcTemplate jdbc;
  private final GuildWorld world;

  /** profile: "28歳・女性・冒険者・グランフォード・白銀の騎士団", so readers can tell who is speaking; null where not needed. */
  public record Ref(long id, String name, String status, String profile) {
    Ref(long id, String name, String status) {
      this(id, name, status, null);
    }

    /** "リネ（28歳・女性・冒険者）" */
    public String described() {
      return profile == null || profile.isEmpty() ? name : name + "（" + profile + "）";
    }
  }

  /** place: where it happened ("guild" or a nation's key). */
  public record Entry(long id, OffsetDateTime at, String kind, String selection, String summary, String body,
                      String writtenBy, String place, List<Ref> people) {
    /** "樫の盾亭（グランフォード）", or the guild's name. */
    public String placeName() {
      var known = GuildPlaces.of(place);
      return known == null ? GuildPlaces.CENTER.title() : known.title();
    }

    public List<String> lines() {
      return body.lines().toList();
    }

    /**
     * The scene as a script: each line either a participant's speech ("リネ「…」", side 0 for the first participant,
     * 1 for the second) or narration (speaker null, side -1).
     */
    public List<Line> script() {
      List<Line> out = new ArrayList<>();
      for (String line : lines()) {
        var speech = java.util.regex.Pattern.compile("^(.+?)「(.*)」$").matcher(line.strip());
        int side = -1;
        if (speech.matches()) {
          for (int i = 0; i < people.size(); i++) if (people.get(i).name().equals(speech.group(1))) side = Math.min(i, 1);
        }
        out.add(side < 0 ? new Line(null, null, -1, line.strip()) : new Line(people.get(side).id(), people.get(side).name(), side, speech.group(2)));
      }
      return List.copyOf(out);
    }

    public String selectionName() {
      return GuildInteractionService.SELECTIONS.getOrDefault(selection, selection);
    }

    /** "/guild/pairs/3-8" for the two participants. */
    public String pairPath() {
      return people.size() < 2 ? null : GuildChronicle.pairPath(people.get(0).id(), people.get(1).id());
    }
  }

  /** One line of a scene: speech (speakerId, name, side 0 or 1) or narration (side -1). */
  public record Line(Long speakerId, String name, int side, String text) {
    public boolean narration() {
      return side < 0;
    }
  }

  public record Change(Ref from, Ref to, String kind, int trust, int affinity, int wariness, int debt,
                       String labelBefore, String labelAfter, String reason, long interactionId, OffsetDateTime at) {
    /** "信頼+3・親しみ+2", or the label change. */
    public String text() {
      if (kind.equals("label")) {
        return labelBefore == null ? GuildInteractionService.LABELS.get(labelAfter) + "になった"
            : GuildInteractionService.LABELS.get(labelBefore) + " → " + GuildInteractionService.LABELS.get(labelAfter);
      }
      List<String> parts = new ArrayList<>();
      add(parts, "信頼", trust);
      add(parts, "親しみ", affinity);
      add(parts, "警戒", wariness);
      add(parts, "恩義", debt);
      return String.join("・", parts);
    }

    private static void add(List<String> parts, String name, int value) {
      if (value != 0) parts.add(name + (value > 0 ? "+" : "") + value);
    }
  }

  public record ThreadView(long id, String kind, String summary, String status, long originId, Long resolvedId, LocalDate expiresOn) {
    public String kindName() {
      return GuildInteractionService.THREADS.getOrDefault(kind, kind);
    }

    public String statusName() {
      return switch (status) {
        case "OPEN" -> "未解決";
        case "RESOLVED" -> "解決";
        default -> "立ち消え";
      };
    }
  }

  /** affiliations: "リネ ／ 派閥：無所属 → 白銀の騎士団（加入）" for each change of village, faction or faith this event made. */
  public record Detail(Entry entry, String outcome, List<Change> changes, List<ThreadView> opened, List<ThreadView> resolved,
                       List<String> affiliations) { }

  /** One relationship of a resident, with the label's history. */
  /** smitten: this resident finds the other their type (the other may not feel the same). */
  public record Relation(Ref other, String label, int trust, int affinity, int wariness, int debt, List<Change> history,
                         String pairPath, boolean smitten) {
    public String labelName() {
      return label == null ? "—" : GuildInteractionService.LABELS.getOrDefault(label, label);
    }
  }

  public record Filter(Long character, Long pairA, Long pairB, String kind, String label) {
    public boolean any() {
      return character != null || pairA != null || kind != null || label != null;
    }
  }

  /** The tavern's current scene: who is talking and what each one says. */
  public record Scene(long interactionId, String summary, Map<Long, String> lines, List<Turn> script) {
    public Scene(long interactionId, String summary, Map<Long, String> lines) {
      this(interactionId, summary, lines, List.of());
    }

    /** This speaker's lines, each with its place in the whole talk (the tavern plays them in turn). */
    public List<Turn> turnsOf(long speaker) {
      return script.stream().filter(turn -> turn.speaker() == speaker).toList();
    }
  }

  /** One line of the current talk: who says it, in what order. */
  public record Turn(int index, long speaker, String text) { }

  public static String pairPath(long a, long b) {
    return "/guild/pairs/" + Math.min(a, b) + "-" + Math.max(a, b);
  }

  private static final String ENTRY = "SELECT i.id, i.happened_at, i.kind, i.selection, i.summary, i.body, i.written_by, i.place FROM T_GUILD_INTERACTION i";

  public List<Entry> list(Filter filter, int page) {
    StringBuilder sql = new StringBuilder(ENTRY + " WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (filter.character() != null) {
      sql.append(" AND EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)");
      args.add(filter.character());
    }
    if (filter.pairA() != null && filter.pairB() != null) {
      sql.append(both());
      args.add(filter.pairA());
      args.add(filter.pairB());
    }
    if (filter.kind() != null) {
      sql.append(" AND i.kind = ?");
      args.add(filter.kind());
    }
    if (filter.label() != null) {
      // Interactions of pairs that hold this label now.
      sql.append(" AND EXISTS (SELECT 1 FROM T_GUILD_BOND b WHERE b.label = ? AND b.low_id = (SELECT MIN(p.character_id) FROM "
          + "T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id) AND b.high_id = (SELECT MAX(p.character_id) FROM "
          + "T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id))");
      args.add(filter.label());
    }
    sql.append(" ORDER BY i.happened_at DESC, i.id DESC LIMIT ").append(PAGE).append(" OFFSET ").append(Math.max(0, page) * PAGE);
    return entries(sql.toString(), args.toArray());
  }

  public List<Entry> recent(int count) {
    return entries(ENTRY + " ORDER BY i.happened_at DESC, i.id DESC LIMIT " + Math.max(1, Math.min(count, 50)));
  }

  public List<String> kinds() {
    return jdbc.queryForList("SELECT DISTINCT kind FROM T_GUILD_INTERACTION ORDER BY kind", String.class);
  }

  public Detail detail(long id) {
    Entry entry = entries(ENTRY + " WHERE i.id = ?", id).stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    String outcome = jdbc.queryForObject("SELECT outcome FROM T_GUILD_INTERACTION WHERE id = ?", String.class, id);
    List<Change> changes = changes(" WHERE e.interaction_id = ? ORDER BY e.id", id);
    List<String> affiliations = new ArrayList<>();
    for (Ref person : entry.people()) {
      // Births and initial values have no interaction (null), so compare without unboxing.
      world.history(person.id()).stream().filter(change -> Long.valueOf(id).equals(change.interactionId()))
          .forEach(change -> affiliations.add(person.name() + " ／ " + change.text()));
    }
    return new Detail(entry, outcome, changes, threads(" WHERE t.origin_interaction_id = ?", id),
        threads(" WHERE t.resolved_interaction_id = ?", id), affiliations);
  }

  /** Everyone this resident has a relationship with, closest first. */
  public List<Relation> relations(long id) {
    List<Relation> relations = new ArrayList<>();
    jdbc.query("""
        SELECT r.to_id, c.name, c.status, r.trust, r.affinity, r.wariness, r.debt, b.label, r.smitten FROM T_GUILD_RELATION r
        JOIN M_GUILD_CHARACTER c ON c.id = r.to_id
        LEFT JOIN T_GUILD_BOND b ON b.low_id = LEAST(r.from_id, r.to_id) AND b.high_id = GREATEST(r.from_id, r.to_id)
        WHERE r.from_id = ? ORDER BY r.affinity + r.trust DESC, r.to_id
        """, rs -> {
          long other = rs.getLong(1);
          relations.add(new Relation(new Ref(other, rs.getString(2), rs.getString(3)), rs.getString(8), rs.getInt(4), rs.getInt(5),
              rs.getInt(6), rs.getInt(7), changes(" WHERE e.change_kind = 'label' AND ((e.from_id = ? AND e.to_id = ?) OR (e.from_id = ? AND e.to_id = ?)) ORDER BY e.id",
                  id, other, other, id), pairPath(id, other), rs.getBoolean(9)));
        }, id);
    return relations;
  }

  /** Two residents' story: every interaction between them and every label change, oldest first. */
  public List<Entry> pairStory(long a, long b) {
    return entries(ENTRY + " WHERE 1 = 1" + both() + " ORDER BY i.happened_at, i.id", a, b);
  }

  public List<Change> pairLabels(long a, long b) {
    return changes(" WHERE e.change_kind = 'label' AND ((e.from_id = ? AND e.to_id = ?) OR (e.from_id = ? AND e.to_id = ?)) ORDER BY e.id", a, b, b, a);
  }

  public List<ThreadView> pairThreads(long a, long b) {
    return threads(" WHERE (t.a_id = ? AND t.b_id = ?) OR (t.a_id = ? AND t.b_id = ?)", a, b, b, a);
  }

  /** The latest interaction in the current part of the day, if both are in today's tavern. */
  public Scene scene(ZonedDateTime now, LocalDate visitDate) {
    return scene(now, visitDate, GuildPlaces.CENTER);
  }

  /** The newest talk at this place in the current part of the day, if any. */
  public Scene scene(ZonedDateTime now, LocalDate visitDate, GuildPlaces.Place place) {
    var local = now.withZoneSameInstant(GuildService.TOKYO);
    String phase = GuildInteractionService.phase(local.getHour());
    int from = switch (phase) {
      case "morning" -> 0;
      case "afternoon" -> 11;
      default -> 18;
    };
    var start = local.toLocalDate().atTime(from, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    var entry = entries(ENTRY + " WHERE i.happened_at >= ? AND i.happened_at <= ? AND i.place = ? ORDER BY i.happened_at DESC, i.id DESC LIMIT 1",
        start, local.toOffsetDateTime(), place.key()).stream().findFirst();
    if (entry.isEmpty() || !local.toLocalDate().equals(visitDate)) return null;
    Map<Long, String> lines = new LinkedHashMap<>();
    for (Ref person : entry.get().people()) {
      String said = entry.get().lines().stream().filter(line -> line.startsWith(person.name() + "「"))
          .findFirst().map(line -> line.substring(person.name().length())).orElse(null);
      lines.put(person.id(), said);
    }
    // A scene written from the template has no lines: the first participant carries the summary.
    if (lines.values().stream().allMatch(java.util.Objects::isNull)) lines.put(entry.get().people().getFirst().id(), entry.get().summary());
    List<Turn> script = new ArrayList<>();
    for (String line : entry.get().lines()) {
      for (Ref person : entry.get().people()) {
        if (line.startsWith(person.name() + "「")) {
          script.add(new Turn(script.size(), person.id(), line.substring(person.name().length())));
          break;
        }
      }
    }
    return new Scene(entry.get().id(), entry.get().summary(), lines, List.copyOf(script));
  }

  /** Who talked with whom today, newest first, so the tavern can seat each party together. */
  public List<List<Long>> groups(ZonedDateTime now, LocalDate visitDate) {
    return groups(now, visitDate, GuildPlaces.CENTER);
  }

  /** Today's talks at this place so far, newest first, as participant ids. */
  public List<List<Long>> groups(ZonedDateTime now, LocalDate visitDate, GuildPlaces.Place place) {
    var local = now.withZoneSameInstant(GuildService.TOKYO);
    if (!local.toLocalDate().equals(visitDate)) return List.of();
    Map<Long, List<Long>> groups = new LinkedHashMap<>();
    jdbc.query("SELECT p.interaction_id, p.character_id FROM T_GUILD_INTERACTION_PARTICIPANT p JOIN T_GUILD_INTERACTION i ON i.id = p.interaction_id"
            + " WHERE i.happened_at >= ? AND i.happened_at <= ? AND i.place = ? ORDER BY i.happened_at DESC, i.id DESC, p.position",
        (org.springframework.jdbc.core.RowCallbackHandler) rs -> groups.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(rs.getLong(2)),
        visitDate.atStartOfDay(GuildService.TOKYO).toOffsetDateTime(), local.toOffsetDateTime(), place.key());
    return List.copyOf(groups.values());
  }

  public Ref person(long id) {
    return jdbc.query("SELECT id, name, status FROM M_GUILD_CHARACTER WHERE id = ?",
        (rs, row) -> new Ref(rs.getLong(1), rs.getString(2), rs.getString(3)), id).stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static String both() {
    return " AND EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)"
        + " AND EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)";
  }

  private List<Entry> entries(String sql, Object... args) {
    List<Entry> entries = jdbc.query(sql, (rs, row) -> new Entry(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), new ArrayList<>()), args);
    for (Entry entry : entries) {
      entry.people().addAll(jdbc.query("SELECT c.id, c.name, c.status, c.age, c.gender, c.occupation, v.name, f.name, f.faction_key "
          + "FROM T_GUILD_INTERACTION_PARTICIPANT p JOIN M_GUILD_CHARACTER c ON c.id = p.character_id "
          + "JOIN M_GUILD_VILLAGE v ON v.village_key = c.current_village JOIN M_GUILD_FACTION f ON f.faction_key = c.faction "
          + "WHERE p.interaction_id = ? ORDER BY p.position",
          (rs, row) -> new Ref(rs.getLong(1), rs.getString(2), rs.getString(3), profile((Integer) rs.getObject(4), rs.getString(5),
              rs.getString(6), rs.getString(7), GuildWorld.NONE.equals(rs.getString(9)) ? null : rs.getString(8))), entry.id()));
    }
    return entries;
  }

  /** Today's traits, skipping what is unknown (and "no faction"). */
  static String profile(Integer age, String... parts) {
    List<String> out = new ArrayList<>();
    if (age != null) out.add(age + "歳");
    for (String part : parts) if (part != null) out.add(part);
    return String.join("・", out);
  }

  private List<Change> changes(String where, Object... args) {
    return jdbc.query("""
        SELECT e.from_id, f.name, f.status, e.to_id, t.name, t.status, e.change_kind, e.trust_delta, e.affinity_delta,
          e.wariness_delta, e.debt_delta, e.label_before, e.label_after, e.reason, e.interaction_id, i.happened_at
        FROM T_GUILD_RELATION_EVENT e JOIN M_GUILD_CHARACTER f ON f.id = e.from_id JOIN M_GUILD_CHARACTER t ON t.id = e.to_id
        JOIN T_GUILD_INTERACTION i ON i.id = e.interaction_id
        """ + where, (rs, row) -> new Change(new Ref(rs.getLong(1), rs.getString(2), rs.getString(3)),
        new Ref(rs.getLong(4), rs.getString(5), rs.getString(6)), rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getInt(10),
        rs.getInt(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getLong(15), rs.getObject(16, OffsetDateTime.class)), args);
  }

  private List<ThreadView> threads(String where, Object... args) {
    return jdbc.query("SELECT t.id, t.kind, t.summary, t.status, t.origin_interaction_id, t.resolved_interaction_id, t.expires_on "
        + "FROM T_GUILD_THREAD t" + where + " ORDER BY t.id", (rs, row) -> new ThreadView(rs.getLong(1), rs.getString(2),
        rs.getString(3), rs.getString(4), rs.getLong(5), (Long) rs.getObject(6, Long.class), rs.getObject(7, LocalDate.class)), args);
  }
}
