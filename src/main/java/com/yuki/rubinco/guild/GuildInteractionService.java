package com.yuki.rubinco.guild;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Up to six interactions a day between residents in the tavern, twice in each of a few JST hours. Code chooses who,
 * which event card, the outcome, the relationship changes, threads, labels and any change of village,
 * faction or faith; the AI only writes the scene afterwards. Everything for one slot is saved in one
 * transaction, and a slot is never filled twice. Residents who left for today's dungeon run take no part.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuildInteractionService {
  static final Map<String, String> LABELS = ordered("acquaintance", "知人", "friend", "友達", "close_friend", "親友",
      "companion", "仲間", "lover", "恋人", "spouse", "夫婦", "rival", "宿敵", "mentor", "師弟");
  static final Map<String, String> THREADS = ordered("promise", "約束", "debt", "借り", "grudge", "遺恨", "search", "探し物", "crush", "片思い");
  static final Map<String, String> SELECTIONS = ordered("random", "ランダム", "warm", "好感度の高い組", "cold", "好感度の低い組",
      "bond", "関係のある組", "thread", "火種のある組", "affiliation", "村・派閥・信仰の違う組");
  /** The most interactions a day, whatever the configuration says. */
  static final int DAILY_CAP = 12;
  /** How long a change of village, faction or faith counts as recent for event cards. */
  private static final int RECENT_CHANGE_DAYS = 14;
  /** Labels code moves along by itself as the axes grow; the others need an event card. */
  private static final Set<String> AUTO_LABELS = Set.of("acquaintance", "friend", "companion", "close_friend");
  private static final Set<String> COOPERATIVE = Set.of("相談", "協力", "手合わせ", "指南");
  private static final Set<String> PARTNERS = Set.of("lover", "spouse");

  private final JdbcTemplate jdbc;
  private final GuildProperties properties;
  private final GuildCards cards;
  private final GuildInteractionWriter writer;
  private final GuildWorld world;

  /**
   * Where a resident lives, their faction and faith (keys and names), the faction they last left or were
   * expelled from (null if none), kinds of change made to them in the last few days, and their history as text.
   */
  record Affiliation(String home, String village, String faction, String faith, String villageName, String factionName,
                     String faithName, String factionVillage, String formerFaction, Set<String> recentChanges, List<String> history,
                     String homeName) {
    static final Affiliation NONE = new Affiliation("grantford", "grantford", GuildWorld.NONE, GuildWorld.NONE,
        "グランフォード", "無所属", "無信仰", null, null, Set.of(), List.of(), "グランフォード");

    Affiliation(String home, String village, String faction, String faith, String villageName, String factionName,
                String faithName, String factionVillage, String formerFaction, Set<String> recentChanges, List<String> history) {
      this(home, village, faction, faith, villageName, factionName, faithName, factionVillage, formerFaction, recentChanges, history, villageName);
    }

    boolean member() {
      return !faction.equals(GuildWorld.NONE);
    }

    boolean believer() {
      return !faith.equals(GuildWorld.NONE);
    }
  }

  record Person(long id, String name, Integer age, String gender, String occupation, String personality, Affiliation affiliation) {
    Person(long id, String name, Integer age, String gender, String occupation, String personality) {
      this(id, name, age, gender, occupation, personality, Affiliation.NONE);
    }
  }

  /**
   * How one resident feels about another. Every axis is bounded (see V56). smitten: this one finds the
   * other their type (V59); it is decided once, when they first meet, and only one way.
   */
  record Rel(int trust, int affinity, int wariness, int debt, boolean smitten) {
    static final Rel NONE = new Rel(0, 0, 0, 0);

    Rel(int trust, int affinity, int wariness, int debt) {
      this(trust, affinity, wariness, debt, false);
    }

    Rel plus(Map<String, Integer> delta) {
      return new Rel(clamp(trust + delta.getOrDefault("trust", 0), -100, 100),
          clamp(affinity + delta.getOrDefault("affinity", 0), -100, 100),
          clamp(wariness + delta.getOrDefault("wariness", 0), 0, 100), clamp(debt + delta.getOrDefault("debt", 0), 0, 100), smitten);
    }
  }

  /** The chance that an adult finds an adult of the other gender their type when they first meet. */
  static final double SMITTEN_CHANCE = 0.2;
  /** Below this affinity, people of different genders warm to each other slowly (unless smitten). */
  static final int RESERVED_BELOW = 20;

  record OpenThread(long id, String kind, long aId, long bId, String summary) { }

  record Pair(Person a, Person b, Rel ab, Rel ba, String label, OffsetDateTime labelSince, int together, int coop,
              List<OpenThread> threads, String deadName) {
    Pair swap() {
      return new Pair(b, a, ba, ab, label, labelSince, together, coop, threads, deadName);
    }

    int minAffinity() {
      return Math.min(ab.affinity(), ba.affinity());
    }

    int minTrust() {
      return Math.min(ab.trust(), ba.trust());
    }

    int maxWariness() {
      return Math.max(ab.wariness(), ba.wariness());
    }

    /** A crush belongs to the one who has it: {a} must be the one in love. */
    boolean hasThread(String kind) {
      return threads.stream().anyMatch(thread -> thread.kind().equals(kind) && (!kind.equals("crush") || thread.aId() == a.id()));
    }

    /** null when either gender is unknown. */
    Boolean sameGender() {
      return a.gender() == null || b.gender() == null ? null : a.gender().equals(b.gender());
    }
  }

  private record Option(GuildCards.Card card, Pair pair, int weight) { }

  public record Job(String slot, String status, String message) { }

  public static String phase(int hour) {
    return hour < 11 ? "morning" : hour < 18 ? "afternoon" : "evening";
  }

  /** Two slots an hour: "2026-10-25T20:00" for the first half, "2026-10-25T20:30" for the second. */
  public static String slot(ZonedDateTime now) {
    var local = now.withZoneSameInstant(GuildService.TOKYO);
    return local.toLocalDate() + "T" + String.format("%02d", local.getHour()) + (local.getMinute() < 30 ? ":00" : ":30");
  }

  public List<Job> jobs() {
    return jdbc.query("SELECT slot_key, status, message FROM T_GUILD_HOURLY_JOB ORDER BY slot_key DESC LIMIT 24",
        (rs, row) -> new Job(rs.getString(1), rs.getString(2), rs.getString(3)));
  }

  /** The hourly run. Returns what happened, for logs and the maintenance page. */
  @Transactional
  public String runHour(ZonedDateTime now) {
    var local = now.withZoneSameInstant(GuildService.TOKYO);
    LocalDate date = local.toLocalDate();
    String slot = slot(local);
    if (!properties.interactionHours().contains(local.getHour())) return "この時間は交流の枠ではありません。";
    jdbc.queryForObject("SELECT id FROM T_GUILD_LOCK WHERE id = 1 FOR UPDATE", Integer.class);
    List<String> done = jdbc.queryForList("SELECT message FROM T_GUILD_HOURLY_JOB WHERE slot_key = ?", String.class, slot);
    if (!done.isEmpty()) return "この時間（" + slot + "）はすでに処理済みです：" + done.getFirst();
    jdbc.update("UPDATE T_GUILD_THREAD SET status = 'FADED' WHERE status = 'OPEN' AND expires_on < ?", date);
    int today = jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_INTERACTION WHERE slot_key LIKE ?", Integer.class, date + "T%");
    int max = Math.min(properties.interactionDailyMax(), DAILY_CAP);
    if (today >= max) return record(slot, "SKIPPED", "今日の交流は上限（" + max + "件）に達しました。");
    List<Person> present = jdbc.query("""
        SELECT c.id, c.name, c.age, c.gender, c.occupation, c.personality, c.home_village, c.current_village, c.faction, c.faith,
          v2.name, f.name, fi.name, f.village_key, hv.name FROM T_GUILD_VISIT v
        JOIN M_GUILD_CHARACTER c ON c.id = v.character_id JOIN M_GUILD_VILLAGE v2 ON v2.village_key = c.current_village
        JOIN M_GUILD_VILLAGE hv ON hv.village_key = c.home_village
        JOIN M_GUILD_FACTION f ON f.faction_key = c.faction JOIN M_GUILD_FAITH fi ON fi.faith_key = c.faith
        WHERE v.visit_date = ? AND c.status = 'ALIVE' AND NOT EXISTS (SELECT 1 FROM T_GUILD_EXPEDITION_MEMBER m
          JOIN T_GUILD_EXPEDITION e ON e.id = m.expedition_id WHERE m.character_id = c.id AND e.expedition_date = ? AND e.departed_at <= ?)
        ORDER BY v.seat
        """, (rs, row) -> new Person(rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
        rs.getString(5), rs.getString(6), affiliation(rs.getLong(1), rs.getString(7), rs.getString(8), rs.getString(9),
        rs.getString(10), rs.getString(11), rs.getString(12), GuildWorld.faithName(rs.getString(13), rs.getString(10), rs.getString(8)),
        rs.getString(14), rs.getString(15), date)), date, date, local.toOffsetDateTime());
    // Those who left for the dungeon today are gone for the night (GuildDungeon).
    if (present.size() < 2) return record(slot, "SKIPPED", "酒場にいる住人が2人未満でした。");

    List<Pair> pairs = new ArrayList<>();
    for (int i = 0; i < present.size(); i++) {
      for (int j = i + 1; j < present.size(); j++) pairs.add(pair(present.get(i), present.get(j)));
    }
    String mode = draw(properties.selectionWeights());
    List<Pair> candidates = pairs.stream().filter(pair -> fits(mode, pair)).toList();
    String selection = candidates.isEmpty() ? "random" : mode;
    Pair chosen = pickPair(candidates.isEmpty() ? pairs : candidates);

    String phase = phase(local.getHour());
    // At most one big event (a confession, a proposal, a falling-out, an apprenticeship) a day.
    boolean majorToday = jdbc.queryForList("SELECT card_id FROM T_GUILD_INTERACTION WHERE slot_key LIKE ?", String.class, date + "T%")
        .stream().anyMatch(majorIds()::contains);
    List<Option> options = new ArrayList<>();
    for (GuildCards.Card card : cards.cards()) {
      if (majorToday && card.tier().equals("major")) continue;
      for (Pair oriented : List.of(chosen, chosen.swap())) {
        if (matches(card, oriented, phase, date)) {
          options.add(new Option(card, oriented, card.weight() * boost(selection, card)));
          break;
        }
      }
    }
    if (options.isEmpty()) return record(slot, "SKIPPED", "この組に合う出来事がありませんでした。");
    Option option = pickOption(options);
    GuildCards.Card card = option.card();
    Pair pair = option.pair();
    GuildCards.Outcome outcome = pickOutcome(card.outcomes());

    // Everything below is decided before the AI sees anything.
    // "both" is rolled for each direction, so the two rarely warm by the same amount; gender and taste shape it further.
    Map<String, Integer> abDelta = attraction(sum(roll(outcome.both()), roll(outcome.aToB())), pair, pair.ab());
    Map<String, Integer> baDelta = attraction(sum(roll(outcome.both()), roll(outcome.bToA())), pair, pair.ba());
    Rel ab = pair.ab().plus(abDelta);
    Rel ba = pair.ba().plus(baDelta);
    int together = pair.together() + 1;
    int coop = pair.coop() + (COOPERATIVE.contains(card.kind()) ? 1 : 0);
    String before = pair.label();
    String after = label(pair, ab, ba, outcome.label(), together, coop, local.toOffsetDateTime());
    String summary = fill(outcome.summary(), pair);
    OpenThread resolved = outcome.resolve() == null ? null : pair.threads().stream()
        .filter(thread -> thread.kind().equals(outcome.resolve())).findFirst().orElse(null);
    String opened = outcome.open() == null ? null : fill(outcome.open().summary(), pair);
    // One who is smitten and warm while the other stays cool: a one-sided love begins, unless the card opens something itself.
    Pair crush = outcome.open() != null ? null : unrequited(pair, ab, ba) ? pair : unrequited(pair.swap(), ba, ab) ? pair.swap() : null;
    if (crush != null) opened = crush.a().name() + "は" + crush.b().name() + "に片思いしている";
    Change change = outcome.change() == null ? null : change(outcome.change(), pair);
    Set<String> keys = new HashSet<>();
    for (Person person : List.of(pair.a(), pair.b())) {
      keys.addAll(List.of(person.affiliation().village(), person.affiliation().faction(), person.affiliation().faith()));
    }

    var scene = new GuildInteractionWriter.Scene(phase, card.kind(), summary, pair.a(), pair.b(),
        describe(pair.ab()), describe(pair.ba()), LABELS.get(before == null ? "acquaintance" : before),
        after.equals(before) ? null : LABELS.get(after), changes(abDelta, pair.a().name(), pair.b().name())
        + changes(baDelta, pair.b().name(), pair.a().name()), pair.threads().stream().map(OpenThread::summary).limit(3).toList(),
        opened, resolved == null ? null : resolved.summary(), pair.deadName(), world.describe(keys),
        change == null ? null : change.text(), change == null ? null : change.kind());
    String body;
    String writtenBy;
    try {
      body = writer.write(scene);
      writtenBy = "ai";
    } catch (GuildInteractionWriter.WritingException failure) {
      log.warn("Guild interaction writing failed ({}, {})", failure.call() ? "bedrock call" : "invalid output", failure.getMessage());
      if (!properties.interactionFallback().equals("template")) {
        return record(slot, "FAILED", (failure.call() ? "Bedrockの呼び出しに失敗しました（" : "AIの応答が形式に合いませんでした（")
            + failure.getMessage() + "）。この時間の交流は見送りました。");
      }
      body = fill(outcome.template(), pair);
      writtenBy = "template";
    }

    jdbc.update("INSERT INTO T_GUILD_INTERACTION (slot_key,happened_at,card_id,cards_version,kind,selection,outcome,summary,body,written_by) "
        + "VALUES (?,?,?,?,?,?,?,?,?,?)", slot, local.toOffsetDateTime(), card.id(), cards.version(), card.kind(), selection,
        outcome.id(), summary, body, writtenBy);
    long id = jdbc.queryForObject("SELECT id FROM T_GUILD_INTERACTION WHERE slot_key = ?", Long.class, slot);
    jdbc.update("INSERT INTO T_GUILD_INTERACTION_PARTICIPANT (interaction_id,character_id,position) VALUES (?,?,0)", id, pair.a().id());
    jdbc.update("INSERT INTO T_GUILD_INTERACTION_PARTICIPANT (interaction_id,character_id,position) VALUES (?,?,1)", id, pair.b().id());
    save(id, pair.a().id(), pair.b().id(), ab, abDelta, card.kind() + "：" + summary);
    save(id, pair.b().id(), pair.a().id(), ba, baDelta, card.kind() + "：" + summary);
    if (!after.equals(before)) bond(id, pair, before, after, local.toOffsetDateTime(), summary);
    if (resolved != null) {
      jdbc.update("UPDATE T_GUILD_THREAD SET status = 'RESOLVED', resolved_interaction_id = ? WHERE id = ?", id, resolved.id());
    }
    if (outcome.open() != null) {
      jdbc.update("INSERT INTO T_GUILD_THREAD (kind,a_id,b_id,summary,origin_interaction_id,expires_on) VALUES (?,?,?,?,?,?)",
          outcome.open().kind(), pair.a().id(), pair.b().id(), opened, id, date.plusDays(outcome.open().days()));
    }
    if (crush != null) {
      jdbc.update("INSERT INTO T_GUILD_THREAD (kind,a_id,b_id,summary,origin_interaction_id,expires_on) VALUES ('crush',?,?,?,?,?)",
          crush.a().id(), crush.b().id(), opened, id, date.plusDays(21));
    }
    if (change != null) {
      jdbc.update("UPDATE M_GUILD_CHARACTER SET " + change.column() + " = ? WHERE id = ?", change.to(), pair.a().id());
      jdbc.update("INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id,kind,from_value,to_value,change_kind,reason,interaction_id,changed_at) "
          + "VALUES (?,?,?,?,?,?,?,?)", pair.a().id(), change.field(), change.from(), change.to(), change.kind(), shorten(summary), id,
          local.toOffsetDateTime());
    }
    return record(slot, "SUCCEEDED", summary + "（" + SELECTIONS.get(selection) + "）");
  }

  private String record(String slot, String status, String message) {
    jdbc.update("INSERT INTO T_GUILD_HOURLY_JOB (slot_key,status,message) VALUES (?,?,?)", slot, status, message);
    return message;
  }

  private Pair pair(Person a, Person b) {
    Rel ab = rel(a, b);
    Rel ba = rel(b, a);
    long low = Math.min(a.id(), b.id());
    long high = Math.max(a.id(), b.id());
    var bond = jdbc.query("SELECT label, since FROM T_GUILD_BOND WHERE low_id = ? AND high_id = ?",
        (rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, OffsetDateTime.class)), low, high).stream().findFirst();
    String together = """
        SELECT COUNT(*) FROM T_GUILD_INTERACTION i
        WHERE EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)
          AND EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)
        """;
    int count = jdbc.queryForObject(together, Integer.class, a.id(), b.id());
    List<String> coopKinds = List.copyOf(COOPERATIVE);
    int coop = jdbc.queryForObject(together + " AND i.kind IN (?,?,?,?)", Integer.class,
        a.id(), b.id(), coopKinds.get(0), coopKinds.get(1), coopKinds.get(2), coopKinds.get(3));
    List<OpenThread> threads = jdbc.query("SELECT id, kind, a_id, b_id, summary FROM T_GUILD_THREAD WHERE status = 'OPEN' "
        + "AND ((a_id = ? AND b_id = ?) OR (a_id = ? AND b_id = ?)) ORDER BY id",
        (rs, row) -> new OpenThread(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5)),
        a.id(), b.id(), b.id(), a.id());
    // Someone both cared for who has died: only survivors take part in remembering them.
    String dead = jdbc.query("""
        SELECT c.name FROM M_GUILD_CHARACTER c WHERE c.status = 'DEAD'
          AND EXISTS (SELECT 1 FROM T_GUILD_RELATION r WHERE r.from_id = ? AND r.to_id = c.id AND r.affinity >= 20)
          AND EXISTS (SELECT 1 FROM T_GUILD_RELATION r WHERE r.from_id = ? AND r.to_id = c.id AND r.affinity >= 20)
        ORDER BY c.id LIMIT 1
        """, (rs, row) -> rs.getString(1), a.id(), b.id()).stream().findFirst().orElse(null);
    return new Pair(a, b, ab, ba, bond.map(Map.Entry::getKey).orElse(null), bond.map(Map.Entry::getValue).orElse(null),
        count, coop, threads, dead);
  }

  private Affiliation affiliation(long id, String home, String village, String faction, String faith, String villageName,
                                  String factionName, String faithName, String factionVillage, String homeName, LocalDate date) {
    var history = world.history(id);
    String former = history.stream().filter(change -> change.kind().equals("faction")).reduce((first, second) -> second)
        .filter(change -> Set.of("leave", "expel").contains(change.changeKind())).map(GuildWorld.Change::fromKey).orElse(null);
    OffsetDateTime since = start(date.minusDays(RECENT_CHANGE_DAYS));
    Set<String> recent = new HashSet<>();
    history.stream().filter(change -> GuildCards.CHANGES.contains(change.changeKind()) && !change.at().isBefore(since))
        .forEach(change -> recent.add(change.changeKind()));
    // Only changes that happened here; where they started out is already in the village, faction and faith.
    List<String> lines = history.stream().filter(change -> GuildCards.CHANGES.contains(change.changeKind()))
        .map(change -> change.day().getMonthValue() + "月" + change.day().getDayOfMonth() + "日 " + change.text() + "：" + change.reason())
        .toList();
    return new Affiliation(home, village, faction, faith, villageName, factionName, faithName, factionVillage, former, Set.copyOf(recent),
        lines.subList(Math.max(0, lines.size() - 3), lines.size()), homeName);
  }

  /** A change code makes to {a}: which column, the history kind, from and to (keys), and the text the writer sees. */
  record Change(String kind, String field, String column, String from, String to, String text) { }

  /** Whether code may make this change to {a} with {b}; cards whose changes can't happen are not drawn. */
  static boolean feasible(String change, Pair pair) {
    var a = pair.a().affiliation();
    var b = pair.b().affiliation();
    return switch (change) {
      case "join" -> !a.member() && b.member() && GuildWorld.canJoin(pair.a().occupation(), a.village(), b.factionVillage());
      case "leave" -> a.member();
      case "expel" -> a.member() && a.faction().equals(b.faction());
      case "move" -> !a.member() && !a.village().equals(b.village());
      case "convert" -> b.believer() && !a.faith().equals(b.faith());
      default -> false;
    };
  }

  private static Change change(String kind, Pair pair) {
    var a = pair.a().affiliation();
    var b = pair.b().affiliation();
    String name = pair.a().name();
    return switch (kind) {
      case "join" -> new Change(kind, "faction", "faction", a.faction(), b.faction(), name + "が" + b.factionName() + "に加わる（加入）");
      case "leave" -> new Change(kind, "faction", "faction", a.faction(), GuildWorld.NONE, name + "が" + a.factionName() + "を抜ける（脱退）");
      case "expel" -> new Change(kind, "faction", "faction", a.faction(), GuildWorld.NONE, name + "が" + a.factionName() + "から追われる（追放）");
      case "move" -> new Change(kind, "village", "current_village", a.village(), b.village(),
          name + "が" + a.villageName() + "から" + b.villageName() + "へ移り住む（移住）");
      case "convert" -> new Change(kind, "faith", "faith", a.faith(), b.faith(), name + "が" + a.faithName() + "から" + b.faithName() + "へ改める（改宗）");
      default -> throw new IllegalStateException("Unknown change " + kind);
    };
  }

  /** For two who have never met, whether from finds to their type is drawn now and saved with the first interaction. */
  private Rel rel(Person from, Person to) {
    return jdbc.query("SELECT trust, affinity, wariness, debt, smitten FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?",
        (rs, row) -> new Rel(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getBoolean(5)), from.id(), to.id()).stream().findFirst()
        .orElseGet(() -> new Rel(0, 0, 0, 0, adult(from) && adult(to) && from.gender() != null && to.gender() != null
            && !from.gender().equals(to.gender()) && ThreadLocalRandom.current().nextDouble() < SMITTEN_CHANCE));
  }

  /**
   * How much warmer one direction really gets. Same gender (or unknown): as the card says. Different
   * genders: reserved at first (below RESERVED_BELOW affinity the gain is halved and a third of the time
   * nothing), unless this one finds the other their type, which makes every gain bigger.
   */
  static Map<String, Integer> attraction(Map<String, Integer> delta, Pair pair, Rel from) {
    int gain = delta.getOrDefault("affinity", 0);
    Boolean same = pair.sameGender();
    if (gain <= 0 || same == null || same) return delta;
    Map<String, Integer> shaped = new LinkedHashMap<>(delta);
    if (from.smitten()) shaped.put("affinity", gain + (gain + 1) / 2 + 1);
    else if (from.affinity() < RESERVED_BELOW) shaped.put("affinity", ThreadLocalRandom.current().nextInt(3) == 0 ? 0 : (gain + 1) / 2);
    return shaped;
  }

  /** {a} is smitten with {b} and warm (affinity 40+) while {b} stays cool (below 20), and no crush is open yet. */
  private static boolean unrequited(Pair pair, Rel ab, Rel ba) {
    return ab.smitten() && ab.affinity() >= 40 && ba.affinity() < 20 && !pair.hasThread("crush")
        && !PARTNERS.contains(pair.label() == null ? "" : pair.label());
  }

  private static boolean fits(String mode, Pair pair) {
    return switch (mode) {
      case "warm" -> pair.minAffinity() + pair.minTrust() >= 30;
      case "cold" -> pair.maxWariness() >= 20 || pair.minAffinity() <= -10;
      case "bond" -> pair.label() != null && !pair.label().equals("acquaintance");
      case "thread" -> !pair.threads().isEmpty();
      case "affiliation" -> {
        var a = pair.a().affiliation();
        var b = pair.b().affiliation();
        yield !a.village().equals(b.village()) || (a.member() && b.member() && !a.faction().equals(b.faction()))
            || (a.believer() && b.believer() && !a.faith().equals(b.faith()));
      }
      default -> true;
    };
  }

  /** Pairs from the last few interactions are drawn a quarter as often, so the same two don't hog the tavern. */
  private Pair pickPair(List<Pair> pairs) {
    Set<String> recent = new HashSet<>(jdbc.queryForList("""
        SELECT CONCAT(CAST(MIN(p.character_id) AS VARCHAR), '-', CAST(MAX(p.character_id) AS VARCHAR))
        FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id IN
          (SELECT id FROM T_GUILD_INTERACTION ORDER BY id DESC LIMIT ?) GROUP BY p.interaction_id
        """, String.class, properties.recentPairSlots()));
    Map<Pair, Integer> weights = new LinkedHashMap<>();
    for (Pair pair : pairs) {
      String key = Math.min(pair.a().id(), pair.b().id()) + "-" + Math.max(pair.a().id(), pair.b().id());
      weights.put(pair, recent.contains(key) ? 1 : 4);
    }
    return draw(weights);
  }

  private boolean matches(GuildCards.Card card, Pair pair, String phase, LocalDate date) {
    var when = card.when();
    String label = pair.label();
    if (!when.labels().isEmpty() && (label == null || !when.labels().contains(label))) return false;
    if (label != null && when.notLabels().contains(label)) return false;
    if (!when.phases().isEmpty() && !when.phases().contains(phase)) return false;
    if (when.affinityMin() != null && pair.minAffinity() < when.affinityMin()) return false;
    if (when.affinityMax() != null && pair.minAffinity() > when.affinityMax()) return false;
    if (when.trustMin() != null && pair.minTrust() < when.trustMin()) return false;
    if (when.warinessMin() != null && pair.maxWariness() < when.warinessMin()) return false;
    if (when.thread() != null && !pair.hasThread(when.thread())) return false;
    if (when.adults() && !(adult(pair.a()) && adult(pair.b()))) return false;
    if (when.ageGapMin() != null && (pair.a().age() == null || pair.b().age() == null
        || pair.a().age() - pair.b().age() < when.ageGapMin())) return false;
    if (when.memorial() && pair.deadName() == null) return false;
    if (when.gender() != null && (pair.sameGender() == null || pair.sameGender() != when.gender().equals("same"))) return false;
    if (when.smitten() && !pair.ab().smitten()) return false;
    if (when.aHome() != null && !when.aHome().equals(pair.a().affiliation().home())) return false;
    if (when.homes() != null && when.homes().equals("same") != pair.a().affiliation().home().equals(pair.b().affiliation().home())) return false;
    var a = pair.a().affiliation();
    var b = pair.b().affiliation();
    if (when.faction() != null && (when.faction().equals("same") != (a.member() && a.faction().equals(b.faction())))) return false;
    if (when.faction() != null && when.faction().equals("different") && a.faction().equals(b.faction())) return false;
    if (when.faith() != null && (when.faith().equals("same") != (a.believer() && a.faith().equals(b.faith())))) return false;
    if (when.faith() != null && when.faith().equals("different") && a.faith().equals(b.faith())) return false;
    if (when.village() != null && when.village().equals("same") != a.village().equals(b.village())) return false;
    if (when.aFaction() != null && when.aFaction().equals("member") != a.member()) return false;
    if (when.bFaction() != null && when.bFaction().equals("member") != b.member()) return false;
    if (when.aFaith() != null && when.aFaith().equals("believer") != a.believer()) return false;
    if (when.bFaith() != null && when.bFaith().equals("believer") != b.believer()) return false;
    if (!when.aChanged().isEmpty() && when.aChanged().stream().noneMatch(a.recentChanges()::contains)) return false;
    if (when.former() && (a.formerFaction() == null || !a.formerFaction().equals(b.faction()))) return false;
    if (card.changes().stream().anyMatch(change -> !feasible(change, pair))) return false;
    if (when.cooldownDays() != null) {
      int recent = jdbc.queryForObject("""
          SELECT COUNT(*) FROM T_GUILD_INTERACTION i WHERE i.card_id = ? AND i.happened_at >= ?
            AND EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)
            AND EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)
          """, Integer.class, card.id(), start(date.minusDays(when.cooldownDays())), pair.a().id(), pair.b().id());
      if (recent > 0) return false;
    }
    return true;
  }

  private static boolean adult(Person person) {
    return person.age() != null && person.age() >= 18;
  }

  /** The selection route makes its own kind of card likelier. */
  private static int boost(String selection, GuildCards.Card card) {
    return switch (selection) {
      case "thread" -> card.tier().equals("thread") ? 4 : 1;
      case "cold" -> card.kind().equals("口論") || card.kind().equals("和解") ? 3 : 1;
      case "warm", "bond" -> card.tier().equals("progress") || card.tier().equals("major") ? 2 : 1;
      case "affiliation" -> card.when().affiliation() || !card.changes().isEmpty() ? 3 : 1;
      default -> 1;
    };
  }

  /**
   * The pair's label after this interaction. Code alone moves labels: friends, companions and close
   * friends follow the axes; lovers, spouses, rivals and mentors need a card's attempt and its rules.
   */
  String label(Pair pair, Rel ab, Rel ba, String attempt, int together, int coop, OffsetDateTime now) {
    String current = pair.label() == null ? "acquaintance" : pair.label();
    int minAffinity = Math.min(ab.affinity(), ba.affinity());
    int minTrust = Math.min(ab.trust(), ba.trust());
    int maxWariness = Math.max(ab.wariness(), ba.wariness());
    String next = current;
    if (attempt != null) {
      switch (attempt) {
        case "lover" -> {
          if (adult(pair.a()) && adult(pair.b()) && minAffinity >= 50 && !Set.of("rival", "lover", "spouse").contains(current)
              && !hasPartner(pair.a().id()) && !hasPartner(pair.b().id())) next = "lover";
        }
        case "spouse" -> {
          if (current.equals("lover") && pair.labelSince() != null && !pair.labelSince().isAfter(now.minusDays(7))
              && minAffinity >= 70 && minTrust >= 60) next = "spouse";
        }
        case "rival" -> {
          if ((maxWariness >= 60 || minAffinity <= -40) && !PARTNERS.contains(current)) next = "rival";
        }
        case "mentor" -> {
          if (pair.a().age() != null && pair.b().age() != null && pair.a().age() - pair.b().age() >= 10
              && ba.trust() >= 30 && AUTO_LABELS.contains(current)) next = "mentor";
        }
        case "acquaintance" -> {
          if (current.equals("rival") && maxWariness < 30) next = "acquaintance";
        }
        default -> { }
      }
    }
    if (AUTO_LABELS.contains(next)) {
      if (minAffinity < 0) next = "acquaintance";
      else if (minAffinity >= 60 && minTrust >= 50 && together >= 8 && !next.equals("acquaintance")) next = "close_friend";
      else if (next.equals("acquaintance") && minTrust >= 40 && coop >= 3) next = "companion";
      else if (next.equals("acquaintance") && minAffinity >= 30 && together >= 3) next = "friend";
    }
    // A pair that never met has no label yet; its first interaction makes them acquaintances.
    return pair.label() == null && next.equals("acquaintance") ? "acquaintance" : next;
  }

  /** Lovers and spouses are exclusive: one at a time. */
  private boolean hasPartner(long id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_BOND WHERE (low_id = ? OR high_id = ?) AND label IN ('lover', 'spouse')",
        Integer.class, id, id) > 0;
  }

  /**
   * A dungeon run is back: every two members feel it, both ways. A clean success brings them closer, a partial one a
   * little; a retreat usually leaves some blame and unease, but now and then surviving it together is what binds them.
   * Different genders warm the same reserved way as in the tavern (attraction). Runs under the caller's guild lock.
   */
  void expedition(long expedition, List<Long> members, String result, String dungeon) {
    if (members.size() < 2) return;
    Map<Long, Person> people = new LinkedHashMap<>();
    for (long id : members) {
      jdbc.query("SELECT id, name, age, gender, occupation, personality FROM M_GUILD_CHARACTER WHERE id = ?",
          (rs, row) -> new Person(rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4), rs.getString(5), rs.getString(6)), id)
          .forEach(person -> people.put(person.id(), person));
    }
    var random = ThreadLocalRandom.current();
    for (Person from : people.values()) {
      for (Person to : people.values()) {
        if (from.id() == to.id()) continue;
        Rel rel = rel(from, to);
        Map<String, Integer> delta = new LinkedHashMap<>();
        String reason;
        switch (result) {
          case "success" -> {
            delta.put("trust", 5 + random.nextInt(5));
            delta.put("affinity", 3 + random.nextInt(4));
            reason = dungeon + "を共に踏破した";
          }
          case "partial" -> {
            delta.put("trust", 2 + random.nextInt(4));
            delta.put("affinity", 1 + random.nextInt(3));
            reason = dungeon + "で背中を預け合った";
          }
          default -> {
            if (random.nextInt(100) < 35) {
              delta.put("trust", 2 + random.nextInt(3));
              reason = dungeon + "からの撤退を共に生き延びた";
            } else {
              delta.put("trust", -(2 + random.nextInt(4)));
              delta.put("wariness", 2 + random.nextInt(4));
              reason = dungeon + "から撤退し、気まずさが残った";
            }
          }
        }
        delta = attraction(delta, new Pair(from, to, rel, Rel.NONE, null, null, 0, 0, List.of(), null), rel);
        store(from.id(), to.id(), rel.plus(delta));
        if (delta.values().stream().allMatch(value -> value == 0)) continue;
        jdbc.update("INSERT INTO T_GUILD_RELATION_EVENT (expedition_id,from_id,to_id,change_kind,trust_delta,affinity_delta,"
            + "wariness_delta,debt_delta,reason) VALUES (?,?,?,'axes',?,?,?,0,?)", expedition, from.id(), to.id(),
            delta.getOrDefault("trust", 0), delta.getOrDefault("affinity", 0), delta.getOrDefault("wariness", 0), shorten(reason));
      }
    }
  }

  private void store(long from, long to, Rel rel) {
    int updated = jdbc.update("UPDATE T_GUILD_RELATION SET trust = ?, affinity = ?, wariness = ?, debt = ?, updated_at = CURRENT_TIMESTAMP "
        + "WHERE from_id = ? AND to_id = ?", rel.trust(), rel.affinity(), rel.wariness(), rel.debt(), from, to);
    if (updated == 0) {
      jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt,smitten) VALUES (?,?,?,?,?,?,?)",
          from, to, rel.trust(), rel.affinity(), rel.wariness(), rel.debt(), rel.smitten());
    }
  }

  private void save(long interaction, long from, long to, Rel rel, Map<String, Integer> delta, String reason) {
    store(from, to, rel);
    if (delta.values().stream().allMatch(value -> value == 0)) return;
    jdbc.update("INSERT INTO T_GUILD_RELATION_EVENT (interaction_id,from_id,to_id,change_kind,trust_delta,affinity_delta,"
        + "wariness_delta,debt_delta,reason) VALUES (?,?,?,'axes',?,?,?,?,?)", interaction, from, to,
        delta.getOrDefault("trust", 0), delta.getOrDefault("affinity", 0), delta.getOrDefault("wariness", 0),
        delta.getOrDefault("debt", 0), shorten(reason));
  }

  private void bond(long interaction, Pair pair, String before, String after, OffsetDateTime now, String summary) {
    long low = Math.min(pair.a().id(), pair.b().id());
    long high = Math.max(pair.a().id(), pair.b().id());
    Long lead = after.equals("mentor") ? pair.a().id() : null;
    if (before == null) {
      jdbc.update("INSERT INTO T_GUILD_BOND (low_id,high_id,label,lead_id,since) VALUES (?,?,?,?,?)", low, high, after, lead, now);
    } else {
      jdbc.update("UPDATE T_GUILD_BOND SET label = ?, lead_id = ?, since = ? WHERE low_id = ? AND high_id = ?", after, lead, now, low, high);
    }
    String reason = before == null ? "初めて言葉を交わした：" + summary
        : LABELS.get(before) + "から" + LABELS.get(after) + "へ：" + summary;
    jdbc.update("INSERT INTO T_GUILD_RELATION_EVENT (interaction_id,from_id,to_id,change_kind,label_before,label_after,reason) "
        + "VALUES (?,?,?,'label',?,?,?)", interaction, pair.a().id(), pair.b().id(), before, after, shorten(reason));
  }

  private List<String> majorIds() {
    return cards.cards().stream().filter(card -> card.tier().equals("major")).map(GuildCards.Card::id).toList();
  }

  private static String describe(Rel rel) {
    return "信頼" + rel.trust() + "・親しみ" + rel.affinity() + "・警戒" + rel.wariness() + "・恩義" + rel.debt()
        + (rel.smitten() ? "・好みのタイプで気になっている（相手は知らない）" : "");
  }

  /** "リネのロアンへの親しみが増す。" — direction words only, no numbers. */
  private static String changes(Map<String, Integer> delta, String from, String to) {
    Map<String, String> names = ordered("trust", "信頼", "affinity", "親しみ", "wariness", "警戒", "debt", "恩義");
    List<String> parts = new ArrayList<>();
    names.forEach((axis, name) -> {
      int value = delta.getOrDefault(axis, 0);
      if (value != 0) parts.add(name + (value > 0 ? "が増す" : "が減る"));
    });
    return parts.isEmpty() ? "" : from + "から" + to + "へ：" + String.join("、", parts) + "。";
  }

  static String fill(String text, Pair pair) {
    var a = pair.a().affiliation();
    var b = pair.b().affiliation();
    return text.replace("{a_job}", job(pair.a())).replace("{b_job}", job(pair.b()))
        .replace("{a_home}", a.homeName()).replace("{b_home}", b.homeName())
        .replace("{a_faction}", a.factionName()).replace("{b_faction}", b.factionName())
        .replace("{a_faith}", a.faithName()).replace("{b_faith}", b.faithName())
        .replace("{a_village}", a.villageName()).replace("{b_village}", b.villageName())
        .replace("{a}", pair.a().name()).replace("{b}", pair.b().name())
        .replace("{dead}", pair.deadName() == null ? "亡き友" : pair.deadName());
  }

  private static String job(Person person) {
    return person.occupation() == null ? "暮らし" : person.occupation();
  }

  private static Map<String, Integer> roll(GuildCards.Delta delta) {
    Map<String, Integer> values = new LinkedHashMap<>();
    delta.ranges().forEach((axis, range) -> values.put(axis, ThreadLocalRandom.current().nextInt(range[0], range[1] + 1)));
    return values;
  }

  private static Map<String, Integer> sum(Map<String, Integer> left, Map<String, Integer> right) {
    Map<String, Integer> total = new LinkedHashMap<>(left);
    right.forEach((axis, value) -> total.merge(axis, value, Integer::sum));
    return total;
  }

  private static GuildCards.Outcome pickOutcome(List<GuildCards.Outcome> outcomes) {
    Map<GuildCards.Outcome, Integer> weights = new LinkedHashMap<>();
    outcomes.forEach(outcome -> weights.put(outcome, outcome.chance()));
    return draw(weights);
  }

  private static Option pickOption(List<Option> options) {
    Map<Option, Integer> weights = new LinkedHashMap<>();
    options.forEach(option -> weights.put(option, option.weight()));
    return draw(weights);
  }

  private static <T> T draw(Map<T, Integer> weights) {
    int total = weights.values().stream().mapToInt(Integer::intValue).filter(weight -> weight > 0).sum();
    if (total <= 0) return weights.keySet().iterator().next();
    int roll = ThreadLocalRandom.current().nextInt(total);
    for (var entry : weights.entrySet()) {
      if (entry.getValue() <= 0) continue;
      roll -= entry.getValue();
      if (roll < 0) return entry.getKey();
    }
    throw new IllegalStateException("empty weights");
  }

  private static OffsetDateTime start(LocalDate date) {
    return date.atStartOfDay(GuildService.TOKYO).toOffsetDateTime();
  }

  private static String shorten(String text) {
    return text.length() > 200 ? text.substring(0, 200) : text;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static Map<String, String> ordered(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
    return Collections.unmodifiableMap(map);
  }
}
