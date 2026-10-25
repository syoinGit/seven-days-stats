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

  public record Ref(long id, String name, String status) { }

  public record Entry(long id, OffsetDateTime at, String kind, String selection, String summary, String body,
                      String writtenBy, List<Ref> people) {
    public List<String> lines() {
      return body.lines().toList();
    }

    public String selectionName() {
      return GuildInteractionService.SELECTIONS.getOrDefault(selection, selection);
    }

    /** "/guild/pairs/3-8" for the two participants. */
    public String pairPath() {
      return people.size() < 2 ? null : GuildChronicle.pairPath(people.get(0).id(), people.get(1).id());
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

  public record Detail(Entry entry, String outcome, List<Change> changes, List<ThreadView> opened, List<ThreadView> resolved) { }

  /** One relationship of a resident, with the label's history. */
  public record Relation(Ref other, String label, int trust, int affinity, int wariness, int debt, List<Change> history,
                         String pairPath) {
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
  public record Scene(long interactionId, String summary, Map<Long, String> lines) { }

  public static String pairPath(long a, long b) {
    return "/guild/pairs/" + Math.min(a, b) + "-" + Math.max(a, b);
  }

  private static final String ENTRY = "SELECT i.id, i.happened_at, i.kind, i.selection, i.summary, i.body, i.written_by FROM T_GUILD_INTERACTION i";

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
    return new Detail(entry, outcome, changes, threads(" WHERE t.origin_interaction_id = ?", id),
        threads(" WHERE t.resolved_interaction_id = ?", id));
  }

  /** Everyone this resident has a relationship with, closest first. */
  public List<Relation> relations(long id) {
    List<Relation> relations = new ArrayList<>();
    jdbc.query("""
        SELECT r.to_id, c.name, c.status, r.trust, r.affinity, r.wariness, r.debt, b.label FROM T_GUILD_RELATION r
        JOIN M_GUILD_CHARACTER c ON c.id = r.to_id
        LEFT JOIN T_GUILD_BOND b ON b.low_id = LEAST(r.from_id, r.to_id) AND b.high_id = GREATEST(r.from_id, r.to_id)
        WHERE r.from_id = ? ORDER BY r.affinity + r.trust DESC, r.to_id
        """, rs -> {
          long other = rs.getLong(1);
          relations.add(new Relation(new Ref(other, rs.getString(2), rs.getString(3)), rs.getString(8), rs.getInt(4), rs.getInt(5),
              rs.getInt(6), rs.getInt(7), changes(" WHERE e.change_kind = 'label' AND ((e.from_id = ? AND e.to_id = ?) OR (e.from_id = ? AND e.to_id = ?)) ORDER BY e.id",
                  id, other, other, id), pairPath(id, other)));
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
    var local = now.withZoneSameInstant(GuildService.TOKYO);
    String phase = GuildInteractionService.phase(local.getHour());
    int from = switch (phase) {
      case "morning" -> 0;
      case "afternoon" -> 11;
      default -> 18;
    };
    var start = local.toLocalDate().atTime(from, 0).atZone(GuildService.TOKYO).toOffsetDateTime();
    var entry = entries(ENTRY + " WHERE i.happened_at >= ? AND i.happened_at <= ? ORDER BY i.happened_at DESC, i.id DESC LIMIT 1",
        start, local.toOffsetDateTime()).stream().findFirst();
    if (entry.isEmpty() || !local.toLocalDate().equals(visitDate)) return null;
    Map<Long, String> lines = new LinkedHashMap<>();
    for (Ref person : entry.get().people()) {
      String said = entry.get().lines().stream().filter(line -> line.startsWith(person.name() + "「"))
          .findFirst().map(line -> line.substring(person.name().length())).orElse(null);
      lines.put(person.id(), said);
    }
    // A scene written from the template has no lines: the first participant carries the summary.
    if (lines.values().stream().allMatch(java.util.Objects::isNull)) lines.put(entry.get().people().getFirst().id(), entry.get().summary());
    return new Scene(entry.get().id(), entry.get().summary(), lines);
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
        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), new ArrayList<>()), args);
    for (Entry entry : entries) {
      entry.people().addAll(jdbc.query("SELECT c.id, c.name, c.status FROM T_GUILD_INTERACTION_PARTICIPANT p "
          + "JOIN M_GUILD_CHARACTER c ON c.id = p.character_id WHERE p.interaction_id = ? ORDER BY p.position",
          (rs, row) -> new Ref(rs.getLong(1), rs.getString(2), rs.getString(3)), entry.id()));
    }
    return entries;
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
