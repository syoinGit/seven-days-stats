package com.yuki.rubinco.guild;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Provisional dungeon runs. Once a day at the departure time (21:50 JST), 1-4 of the day's visitors leave
 * the tavern for a dungeon. Code simulates the whole run when they leave, from guild/dungeon-cards.yml:
 * the dungeon, the goal, one card for each stage (起承転結) and the result. The stages become readable
 * one every stageMinutes and the result only with the last; nothing unrevealed leaves this class.
 * Members are out of the tavern (and its interactions) for the rest of the day; once back they sit
 * in the tavern again with a line about the run.
 */
@Service
public class GuildDungeon {
  static final List<String> STAGES = List.of("ki", "sho", "ten", "ketsu");
  static final Map<String, String> STAGE_NAMES = Map.of("ki", "起", "sho", "承", "ten", "転", "ketsu", "結");
  static final Map<String, String> RESULTS = Map.of("success", "踏破", "partial", "一部達成", "retreat", "撤退");
  static final Set<String> ROLES = Set.of("fighter", "scout", "healer", "maker");
  static final int PARTY_MAX = 4;

  record Dungeon(String id, String name, String near, String look, String monster, String treasure) { }

  record Goal(String id, String text) { }

  /** min already counts {other}: a card that names another member needs two or more. */
  record Card(String id, String stage, String text, String countered, int weight, int min, int max, String role, List<String> goals,
              boolean heroHome, int danger, String counter, String result) { }

  record Deck(String version, Map<String, Set<String>> roles, List<Dungeon> dungeons, List<Goal> goals, Map<String, String> returns,
              Map<String, List<Card>> stages) { }

  record Member(long id, String name, String occupation, String home) { }

  /** body is null until the stage is revealed. */
  public record Stage(int stage, String name, String body, OffsetDateTime revealAt) {
    public boolean revealed() {
      return body != null;
    }
  }

  /** result is null until the party is back. */
  public record Expedition(long id, LocalDate date, String dungeon, String goal, List<GuildChronicle.Ref> members,
                           List<Stage> stages, String result, OffsetDateTime departedAt, OffsetDateTime returnedAt) {
    public boolean back() {
      return result != null;
    }

    public String resultName() {
      return result == null ? null : RESULTS.get(result);
    }

    public String party() {
      return GuildDungeon.party(members.stream().map(GuildChronicle.Ref::name).toList());
    }

    /** The next stage to be revealed, or null once everything is out. */
    public Stage next() {
      return stages.stream().filter(stage -> !stage.revealed()).findFirst().orElse(null);
    }

    /** The last revealed stage's name ("承"), or null before the first. */
    public String reached() {
      return stages.stream().filter(Stage::revealed).reduce((first, second) -> second).map(Stage::name).orElse(null);
    }
  }

  public record Summary(long id, LocalDate date, String dungeon, String party, String result) {
    public String resultName() {
      return result == null ? "探索中" : RESULTS.get(result);
    }
  }

  private final JdbcTemplate jdbc;
  private final GuildProperties properties;
  private final Deck deck;

  public GuildDungeon(JdbcTemplate jdbc, GuildProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.deck = load(new ClassPathResource("guild/dungeon-cards.yml"));
  }

  Deck deck() {
    return deck;
  }

  /** The scheduled check: leaves only between the departure time and when the last stage would come out. */
  @Transactional
  public String departIfDue(ZonedDateTime now) {
    LocalTime time = now.withZoneSameInstant(GuildService.TOKYO).toLocalTime();
    LocalTime at = properties.dungeonDeparture();
    if (time.isBefore(at) || !time.isBefore(at.plusMinutes(4L * properties.dungeonStageMinutes()))) return "出発の時間帯ではありません。";
    return depart(now);
  }

  /** Sends today's party now (once a day). Returns what happened for logs and the maintenance page. */
  @Transactional
  public String depart(ZonedDateTime now) {
    var local = now.withZoneSameInstant(GuildService.TOKYO).truncatedTo(ChronoUnit.MINUTES);
    LocalDate date = local.toLocalDate();
    jdbc.queryForObject("SELECT id FROM T_GUILD_LOCK WHERE id = 1 FOR UPDATE", Integer.class);
    if (jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_EXPEDITION WHERE expedition_date = ?", Integer.class, date) > 0) {
      return "今日のダンジョン探索はすでに出発しています。";
    }
    List<Member> present = new ArrayList<>(jdbc.query("""
        SELECT c.id, c.name, c.occupation, c.home_village FROM T_GUILD_VISIT v JOIN M_GUILD_CHARACTER c ON c.id = v.character_id
        WHERE v.visit_date = ? AND c.status = 'ALIVE' ORDER BY v.seat
        """, (rs, row) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), date));
    if (present.isEmpty()) return "今日の酒場には誰もいないため、ダンジョンへは誰も向かいませんでした。";
    Collections.shuffle(present, ThreadLocalRandom.current());
    List<Member> party = List.copyOf(present.subList(0, 1 + ThreadLocalRandom.current().nextInt(Math.min(PARTY_MAX, present.size()))));
    Run run = simulate(party, ThreadLocalRandom.current());

    OffsetDateTime departed = local.toOffsetDateTime();
    int minutes = properties.dungeonStageMinutes();
    jdbc.update("INSERT INTO T_GUILD_EXPEDITION (expedition_date,dungeon_key,dungeon_name,goal,result,cards_version,departed_at,returned_at) "
        + "VALUES (?,?,?,?,?,?,?,?)", date, run.dungeon().id(), run.dungeon().name(), run.goal(), run.result(), deck.version(),
        departed, departed.plusMinutes(4L * minutes));
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_EXPEDITION WHERE expedition_date = ?", Long.class, date);
    for (int i = 0; i < party.size(); i++) {
      jdbc.update("INSERT INTO T_GUILD_EXPEDITION_MEMBER (expedition_id,character_id,position) VALUES (?,?,?)", id, party.get(i).id(), i);
    }
    for (int i = 0; i < run.stages().size(); i++) {
      Drawn drawn = run.stages().get(i);
      jdbc.update("INSERT INTO T_GUILD_EXPEDITION_STAGE (expedition_id,stage,card_id,hero_id,body,reveal_at) VALUES (?,?,?,?,?,?)",
          id, i + 1, drawn.card().id(), drawn.hero().id(), drawn.body(), departed.plusMinutes((long) (i + 1) * minutes));
    }
    return party(party.stream().map(Member::name).toList()) + "が" + run.dungeon().name() + "へ向かいました（"
        + departed.plusMinutes(4L * minutes).toLocalTime() + "ごろ戻ります）。";
  }

  record Drawn(Card card, Member hero, String body, boolean countered) { }

  record Run(Dungeon dungeon, String goalId, String goal, List<Drawn> stages, String result) { }

  /**
   * The whole run. 転 sets the danger (one less when someone can counter it); the party's size, a fighter,
   * a healer and a little luck decide whether it ends in success, partial success or retreat, and 結 follows.
   */
  Run simulate(List<Member> party, java.util.random.RandomGenerator random) {
    Dungeon dungeon = deck.dungeons().get(random.nextInt(deck.dungeons().size()));
    Goal goal = deck.goals().get(random.nextInt(deck.goals().size()));
    Map<String, String> vars = new HashMap<>(Map.of("dungeon", dungeon.name(), "look", dungeon.look(), "monster", dungeon.monster(),
        "treasure", dungeon.treasure()));
    vars.put("goal", fill(goal.text(), vars));
    vars.put("party", party(party.stream().map(Member::name).toList()));
    List<Drawn> stages = new ArrayList<>();
    int danger = 0;
    for (String stage : List.of("ki", "sho", "ten")) {
      Drawn drawn = draw(stage, party, goal.id(), dungeon, null, vars, random);
      if (stage.equals("ten")) danger = drawn.card().danger() - (drawn.countered() ? 1 : 0);
      stages.add(drawn);
    }
    int power = party.size() + (has(party, "fighter") ? 1 : 0) + (has(party, "healer") ? 1 : 0) + random.nextInt(3);
    int need = 2 * danger + 1;
    String result = power >= need + 1 ? "success" : power >= need - 1 ? "partial" : "retreat";
    stages.add(draw("ketsu", party, goal.id(), dungeon, result, vars, random));
    return new Run(dungeon, goal.id(), vars.get("goal"), List.copyOf(stages), result);
  }

  private Drawn draw(String stage, List<Member> party, String goal, Dungeon dungeon, String result, Map<String, String> vars,
                     java.util.random.RandomGenerator random) {
    List<Card> options = deck.stages().get(stage).stream()
        .filter(card -> party.size() >= card.min() && party.size() <= card.max())
        .filter(card -> card.role() == null || has(party, card.role()))
        .filter(card -> card.goals().isEmpty() || card.goals().contains(goal))
        .filter(card -> !card.heroHome() || party.stream().anyMatch(member -> dungeon.near().equals(member.home())))
        .filter(card -> result == null || result.equals(card.result()))
        .toList();
    int total = options.stream().mapToInt(Card::weight).sum();
    int roll = random.nextInt(total);
    Card card = options.getLast();
    for (Card option : options) {
      roll -= option.weight();
      if (roll < 0) {
        card = option;
        break;
      }
    }
    Card chosen = card;
    boolean countered = chosen.counter() != null && has(party, chosen.counter());
    List<Member> heroes = countered ? holders(party, chosen.counter())
        : chosen.role() != null ? holders(party, chosen.role())
        : chosen.heroHome() ? party.stream().filter(member -> dungeon.near().equals(member.home())).toList()
        : party;
    Member hero = heroes.get(random.nextInt(heroes.size()));
    List<Member> others = party.stream().filter(member -> member.id() != hero.id()).toList();
    Map<String, String> filled = new HashMap<>(vars);
    filled.put("hero", hero.name());
    if (!others.isEmpty()) filled.put("other", others.get(random.nextInt(others.size())).name());
    return new Drawn(chosen, hero, fill(countered ? chosen.countered() : chosen.text(), filled), countered);
  }

  private boolean has(List<Member> party, String role) {
    return !holders(party, role).isEmpty();
  }

  private List<Member> holders(List<Member> party, String role) {
    Set<String> jobs = deck.roles().getOrDefault(role, Set.of());
    return party.stream().filter(member -> member.occupation() != null && jobs.contains(member.occupation())).toList();
  }

  static String fill(String text, Map<String, String> vars) {
    String out = text;
    for (var entry : vars.entrySet()) out = out.replace("{" + entry.getKey() + "}", entry.getValue());
    return out;
  }

  /** "リネ", "リネとロアン", "リネ、ロアン、カイの三人". */
  static String party(List<String> names) {
    return switch (names.size()) {
      case 0 -> "";
      case 1 -> names.getFirst();
      case 2 -> names.get(0) + "と" + names.get(1);
      default -> String.join("、", names) + "の" + "〇一二三四五六七八九".charAt(names.size()) + "人";
    };
  }

  /** Who of a day's visitors is out on that day's run as of now (null) or back from it (their tavern line). */
  public Map<Long, String> presence(LocalDate date, ZonedDateTime now) {
    Map<Long, String> presence = new HashMap<>();
    jdbc.query("""
        SELECT m.character_id, e.returned_at, e.result FROM T_GUILD_EXPEDITION_MEMBER m JOIN T_GUILD_EXPEDITION e ON e.id = m.expedition_id
        WHERE e.expedition_date = ? AND e.departed_at <= ?
        """, rs -> {
          boolean back = !rs.getObject(2, OffsetDateTime.class).isAfter(now.toOffsetDateTime());
          presence.put(rs.getLong(1), back ? deck.returns().get(rs.getString(3)) : null);
        }, date, now.toOffsetDateTime());
    return presence;
  }

  /** The run that left on this day, as far as it has come by now; null if none has left. */
  public Expedition day(LocalDate date, ZonedDateTime now) {
    return jdbc.queryForList("SELECT id FROM T_GUILD_EXPEDITION WHERE expedition_date = ? AND departed_at <= ?", Long.class,
        date, now.toOffsetDateTime()).stream().findFirst().map(id -> expedition(id, now)).orElse(null);
  }

  /** The newest run that has left by now; null if none. */
  public Expedition latest(ZonedDateTime now) {
    return jdbc.queryForList("SELECT id FROM T_GUILD_EXPEDITION WHERE departed_at <= ? ORDER BY departed_at DESC LIMIT 1", Long.class,
        now.toOffsetDateTime()).stream().findFirst().map(id -> expedition(id, now)).orElse(null);
  }

  /** One run as far as it has come by now; 404 for a run that doesn't exist or hasn't left yet. */
  public Expedition expedition(long id, ZonedDateTime now) {
    OffsetDateTime at = now.toOffsetDateTime();
    var head = jdbc.query("SELECT expedition_date, dungeon_name, goal, result, departed_at, returned_at FROM T_GUILD_EXPEDITION "
        + "WHERE id = ? AND departed_at <= ?", (rs, row) -> new Object[] {rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3),
        rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)}, id, at).stream().findFirst()
        .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    List<GuildChronicle.Ref> members = jdbc.query("""
        SELECT c.id, c.name, c.status, c.age, c.gender, c.occupation FROM T_GUILD_EXPEDITION_MEMBER m
        JOIN M_GUILD_CHARACTER c ON c.id = m.character_id WHERE m.expedition_id = ? ORDER BY m.position
        """, (rs, row) -> new GuildChronicle.Ref(rs.getLong(1), rs.getString(2), rs.getString(3),
        GuildChronicle.profile((Integer) rs.getObject(4), rs.getString(5), rs.getString(6))), id);
    // Unrevealed text never leaves the database query.
    List<Stage> stages = jdbc.query("SELECT stage, CASE WHEN reveal_at <= ? THEN body END, reveal_at FROM T_GUILD_EXPEDITION_STAGE "
        + "WHERE expedition_id = ? ORDER BY stage", (rs, row) -> new Stage(rs.getInt(1), STAGE_NAMES.get(STAGES.get(rs.getInt(1) - 1)),
        rs.getString(2), rs.getObject(3, OffsetDateTime.class)), at, id);
    OffsetDateTime returned = (OffsetDateTime) head[5];
    return new Expedition(id, (LocalDate) head[0], (String) head[1], (String) head[2], members, stages,
        returned.isAfter(at) ? null : (String) head[3], (OffsetDateTime) head[4], returned);
  }

  /** Runs that have left by now, newest first; a resident's only when character is given. */
  public List<Summary> recent(ZonedDateTime now, Long character, int limit) {
    OffsetDateTime at = now.toOffsetDateTime();
    List<Object[]> heads = jdbc.query("SELECT e.id, e.expedition_date, e.dungeon_name, CASE WHEN e.returned_at <= ? THEN e.result END "
        + "FROM T_GUILD_EXPEDITION e WHERE e.departed_at <= ?" + (character == null ? "" : " AND EXISTS (SELECT 1 FROM "
        + "T_GUILD_EXPEDITION_MEMBER m WHERE m.expedition_id = e.id AND m.character_id = ?)") + " ORDER BY e.departed_at DESC LIMIT ?",
        (rs, row) -> new Object[] {rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getString(4)},
        character == null ? new Object[] {at, at, limit} : new Object[] {at, at, character, limit});
    List<Summary> out = new ArrayList<>();
    for (Object[] head : heads) {
      List<String> names = jdbc.queryForList("SELECT c.name FROM T_GUILD_EXPEDITION_MEMBER m JOIN M_GUILD_CHARACTER c "
          + "ON c.id = m.character_id WHERE m.expedition_id = ? ORDER BY m.position", String.class, head[0]);
      out.add(new Summary((Long) head[0], (LocalDate) head[1], (String) head[2], party(names), (String) head[3]));
    }
    return out;
  }

  // ---- loading ----

  static Deck load(ClassPathResource resource) {
    try (InputStream in = resource.getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      Map<String, Set<String>> roles = new LinkedHashMap<>();
      map(root.get("roles")).forEach((role, jobs) -> {
        if (!ROLES.contains(role)) throw new IllegalStateException("Unknown dungeon role " + role);
        roles.put(role, Set.copyOf(strings(jobs)));
      });
      List<Dungeon> dungeons = new ArrayList<>();
      for (Object item : list(root.get("dungeons"))) {
        var m = map(item);
        dungeons.add(new Dungeon(text(m, "id"), text(m, "name"), text(m, "near"), text(m, "look"), text(m, "monster"), text(m, "treasure")));
      }
      List<Goal> goals = new ArrayList<>();
      for (Object item : list(root.get("goals"))) goals.add(new Goal(text(map(item), "id"), text(map(item), "text")));
      Set<String> goalIds = new HashSet<>(goals.stream().map(Goal::id).toList());
      Map<String, String> returns = new LinkedHashMap<>();
      map(root.get("returns")).forEach((result, line) -> returns.put(result, String.valueOf(line)));
      if (!returns.keySet().equals(RESULTS.keySet())) throw new IllegalStateException("Dungeon returns must cover every result");
      Map<String, List<Card>> stages = new LinkedHashMap<>();
      Set<String> ids = new HashSet<>();
      var stageMap = map(root.get("stages"));
      for (String stage : STAGES) {
        List<Card> cards = new ArrayList<>();
        for (Object item : list(stageMap.get(stage))) {
          Card card = card(stage, map(item), roles.keySet(), goalIds);
          if (!ids.add(card.id())) throw new IllegalStateException("Duplicate dungeon card " + card.id());
          cards.add(card);
        }
        stages.put(stage, List.copyOf(cards));
      }
      // Whatever the party, every stage (and every result) has a card that can always be drawn.
      for (String stage : STAGES) {
        for (String result : stage.equals("ketsu") ? List.copyOf(RESULTS.keySet()) : java.util.Collections.<String>singletonList(null)) {
          boolean open = stages.get(stage).stream().anyMatch(card -> card.min() <= 1 && card.max() >= PARTY_MAX && card.role() == null
              && card.goals().isEmpty() && !card.heroHome() && (result == null || result.equals(card.result())));
          if (!open) throw new IllegalStateException("Dungeon stage " + stage + " needs a card for any party" + (result == null ? "" : " (" + result + ")"));
        }
      }
      if (dungeons.isEmpty() || goals.isEmpty()) throw new IllegalStateException("Dungeon cards need dungeons and goals");
      return new Deck(String.valueOf(root.get("version")), Map.copyOf(roles), List.copyOf(dungeons), List.copyOf(goals), Map.copyOf(returns),
          Map.copyOf(stages));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static Card card(String stage, Map<String, Object> m, Set<String> roles, Set<String> goals) {
    String id = text(m, "id");
    String body = text(m, "text");
    String countered = (String) m.get("countered");
    int danger = ((Number) m.getOrDefault("danger", 0)).intValue();
    String counter = (String) m.get("counter");
    String result = (String) m.get("result");
    String role = (String) m.get("role");
    if (stage.equals("ten") != (danger >= 1 && danger <= 3 && counter != null && countered != null)) {
      throw new IllegalStateException("Dungeon card " + id + ": only 転 cards have danger 1-3, counter and countered");
    }
    if (stage.equals("ketsu") != (result != null && RESULTS.containsKey(result))) {
      throw new IllegalStateException("Dungeon card " + id + ": only 結 cards have a result");
    }
    if ((role != null && !roles.contains(role)) || (counter != null && !roles.contains(counter))) {
      throw new IllegalStateException("Dungeon card " + id + ": unknown role");
    }
    List<String> cardGoals = m.containsKey("goals") ? strings(m.get("goals")) : List.of();
    if (!goals.containsAll(cardGoals)) throw new IllegalStateException("Dungeon card " + id + ": unknown goal");
    boolean other = body.contains("{other}") || (countered != null && countered.contains("{other}"));
    int min = Math.max(((Number) m.getOrDefault("min", 1)).intValue(), other ? 2 : 1);
    int max = ((Number) m.getOrDefault("max", PARTY_MAX)).intValue();
    for (String t : countered == null ? List.of(body) : List.of(body, countered)) {
      String left = t.replaceAll("\\{(party|hero|other|dungeon|look|monster|treasure|goal)}", "");
      if (left.contains("{") || left.contains("}")) throw new IllegalStateException("Dungeon card " + id + ": unknown placeholder");
    }
    return new Card(id, stage, body, countered, ((Number) m.getOrDefault("weight", 1)).intValue(), min, max, role, cardGoals,
        Boolean.TRUE.equals(m.get("hero_home")), danger, counter, result);
  }

  private static String text(Map<String, Object> m, String key) {
    Object value = m.get(key);
    if (value == null || String.valueOf(value).isBlank()) throw new IllegalStateException("Dungeon cards: missing " + key);
    return String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Dungeon cards: expected a map");
    return (Map<String, Object>) value;
  }

  private static List<Object> list(Object value) {
    if (!(value instanceof List<?> items)) throw new IllegalStateException("Dungeon cards: expected a list");
    return List.copyOf(items);
  }

  private static List<String> strings(Object value) {
    return list(value).stream().map(String::valueOf).toList();
  }
}
