package com.yuki.rubinco.guild;

import com.yuki.rubinco.watchpoint.ai.config.AiAnalysisProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class GuildService {
  public static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
  private static final int FAILURE_ALLOWANCE = 5;
  private final JdbcTemplate jdbc;
  private final GuildProperties properties;
  private final AiAnalysisProperties ai;
  private final GuildSourceService sources;
  private final GuildCharacterGenerator generator;
  private final GuildPeople people;
  private final GuildDossier dossier;
  private final GuildWorld affiliations;
  private final GuildDungeon dungeon;

  /**
   * memoryLabel and memory are null for residents born before memories were recorded; lineage and look
   * for those born before code chose them (they keep the plain sprite tinted by appearance); reading for
   * those born before the AI read the world around the memory.
   */
  public record Character(long id, LocalDate bornOn, String game, String name, String introduction,
                          String origin, String personality, String appearance, String status,
                          Integer age, String gender, String occupation, String memoryLabel, String memory,
                          String lineage, GuildPeople.Look look, String reading,
                          String homeVillage, String village, String faction, String faith) {
    public List<String> spriteLayers() {
      return spriteLayers(pose());
    }

    public String contour() {
      return GuildPeople.HEAD_SHAPES.get(Math.floorMod(id, GuildPeople.HEAD_SHAPES.size()));
    }

    public String pose() {
      return GuildPeople.pose(personality, "rest");
    }

    public List<String> spriteLayers(String pose) {
      return look == null ? List.of("resident") : look.layers(age, personality, contour(), pose);
    }

    public List<String> portraitLayers() {
      return look == null ? List.of("resident") : look.portraitLayers(age, personality, contour(), pose());
    }

    public String lineageName() {
      var known = GuildPeople.Lineage.of(lineage);
      return known == null ? null : known.label();
    }

    public String gameName() {
      return GuildSourceService.GAMES.getOrDefault(game, game);
    }

    /** "28歳・女性・鍛冶屋", skipping what is unknown. */
    public String profile() {
      List<String> parts = new java.util.ArrayList<>();
      if (age != null) parts.add(age + "歳");
      if (gender != null) parts.add(gender);
      if (occupation != null) parts.add(occupation);
      return String.join("・", parts);
    }

    /** "グランフォード生まれ・エルムウッド在住", or just the birthplace when they never moved. */
    public String place() {
      return homeVillage.equals(village) ? homeVillage + "で暮らす" : homeVillage + "生まれ・" + village + "で暮らす";
    }

    public List<String> memoryLines() {
      return memoryLabel == null || memory == null ? List.of() : memory.lines().toList();
    }
  }
  public record Visit(Character character, int seat, String line) {
    public String pose() { return GuildPeople.pose(line, character.pose()); }
  }
  /** One place's visitors for the day. */
  public record Room(LocalDate date, String phase, List<Visit> visits, GuildPlaces.Place place) {
    public Room(LocalDate date, String phase, List<Visit> visits) {
      this(date, phase, visits, GuildPlaces.CENTER);
    }
  }
  public record Job(LocalDate date, String status, int attempts, String message) { }

  // The source key (it names the player and day) stays private; the memory label and lines are public.
  private static final String PUBLIC_COLUMNS = "c.id, c.born_on, c.game_id, c.name, c.introduction, c.origin, "
      + "c.personality, c.appearance, c.status, c.age, c.gender, c.occupation, s.memory_label, "
      + "c.name_lineage, c.outfit, c.hair_style, c.hair_color, c.skin_color, c.cloth_color, c.cloth2_color, c.accessory, c.eye_color, "
      + "c.eye_style, c.brow_style, c.outfit_variant, "
      + "CASE WHEN s.memory_label IS NULL THEN NULL ELSE s.facts END AS memory, s.reading, "
      + "hv.name AS home_village_name, cv.name AS village_name, cv.village_key, fa.name AS faction_name, "
      + "fi.name AS faith_name, fi.faith_key";
  private static final String FROM = " FROM M_GUILD_CHARACTER c LEFT JOIN T_GUILD_SOURCE s ON s.character_id = c.id"
      + " JOIN M_GUILD_VILLAGE hv ON hv.village_key = c.home_village JOIN M_GUILD_VILLAGE cv ON cv.village_key = c.current_village"
      + " JOIN M_GUILD_FACTION fa ON fa.faction_key = c.faction JOIN M_GUILD_FAITH fi ON fi.faith_key = c.faith";
  private static Character character(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Character(rs.getLong("id"), rs.getDate("born_on").toLocalDate(), rs.getString("game_id"),
        rs.getString("name"), rs.getString("introduction"), rs.getString("origin"),
        rs.getString("personality"), rs.getString("appearance"), rs.getString("status"),
        (Integer) rs.getObject("age"), rs.getString("gender"), rs.getString("occupation"),
        rs.getString("memory_label"), rs.getString("memory"), rs.getString("name_lineage"),
        GuildPeople.Look.of(rs.getString("outfit"), rs.getString("hair_style"), rs.getString("hair_color"),
            rs.getString("skin_color"), rs.getString("cloth_color"), rs.getString("cloth2_color"), rs.getString("gender"),
            rs.getString("accessory"), rs.getString("eye_color"), rs.getString("eye_style"), rs.getString("brow_style"), rs.getString("outfit_variant")),
        rs.getString("reading"), rs.getString("home_village_name"), rs.getString("village_name"), rs.getString("faction_name"),
        GuildWorld.faithName(rs.getString("faith_name"), rs.getString("faith_key"), rs.getString("village_key")));
  }

  /** A resident's affiliation history, oldest first. */
  public List<GuildWorld.Change> history(long id) {
    return affiliations.history(id);
  }

  public List<Character> characters(int page) {
    return jdbc.query("SELECT " + PUBLIC_COLUMNS + FROM + " ORDER BY c.born_on DESC, c.id DESC LIMIT 40 OFFSET ?",
        (rs, row) -> character(rs), Math.max(0, Math.min(page, 10000)) * 40);
  }

  public int bornToday() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM M_GUILD_CHARACTER WHERE born_on = ?", Integer.class, LocalDate.now(TOKYO));
  }

  public Character character(long id) {
    return jdbc.query("SELECT " + PUBLIC_COLUMNS + FROM + " WHERE c.id = ?",
        (rs, row) -> character(rs), id).stream().findFirst().orElseThrow(() ->
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
  }

  /** The unattended guild today. */
  public Room room(ZonedDateTime now) {
    return room(now, GuildPlaces.CENTER);
  }

  /** One place today: who came and what they are doing in this part of the day. */
  public Room room(ZonedDateTime now, GuildPlaces.Place place) {
    var local = now.withZoneSameInstant(TOKYO);
    String phase = local.getHour() < 11 ? "morning" : local.getHour() < 18 ? "afternoon" : "evening";
    // Keep the last established scene readable after downtime, clearly labelled with its date.
    LocalDate date = jdbc.queryForObject("SELECT MAX(visit_date) FROM T_GUILD_DAY WHERE visit_date <= ?", LocalDate.class, local.toLocalDate());
    if (date == null) return new Room(local.toLocalDate(), phase, List.of(), place);
    List<Visit> visits = jdbc.query("SELECT " + PUBLIC_COLUMNS + ", v.seat, v." + phase + " AS line" + FROM
        + " JOIN T_GUILD_VISIT v ON c.id = v.character_id WHERE v.visit_date = ? AND v.place = ? AND c.status = 'ALIVE' ORDER BY v.seat",
        (rs, row) -> new Visit(character(rs), rs.getInt("seat"), rs.getString("line")), date, place.key());
    if (!place.guild()) return new Room(date, phase, visits, place);
    // Out in the dungeon: not in the room. Back from it: in their seat, talking about it.
    var presence = dungeon.presence(date, local);
    visits = visits.stream().filter(visit -> !presence.containsKey(visit.character().id()) || presence.get(visit.character().id()) != null)
        .map(visit -> presence.containsKey(visit.character().id())
            ? new Visit(visit.character(), visit.seat(), presence.get(visit.character().id())) : visit).toList();
    return new Room(date, phase, visits, place);
  }

  /** Where a resident is today, or null when they are not out. */
  public GuildPlaces.Place placeToday(long id) {
    return jdbc.queryForList("SELECT place FROM T_GUILD_VISIT WHERE visit_date = ? AND character_id = ?", String.class,
        LocalDate.now(TOKYO), id).stream().findFirst().map(GuildPlaces::of).orElse(null);
  }

  /** What the AI read about the world for each resident, by resident id. Administrators only. */
  public java.util.Map<Long, String> worlds() {
    java.util.Map<Long, String> worlds = new java.util.HashMap<>();
    jdbc.query("SELECT character_id, world_facts FROM T_GUILD_SOURCE WHERE world_facts IS NOT NULL",
        rs -> { worlds.put(rs.getLong(1), rs.getString(2)); });
    return worlds;
  }

  public List<Job> jobs() {
    return jdbc.query("SELECT target_date, status, attempts, message FROM T_GUILD_JOB ORDER BY target_date DESC LIMIT 30",
        (rs, row) -> new Job(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getInt(3), rs.getString(4)));
  }

  /** One database lock serializes every guild write across processes. Reads never invoke this. */
  private void lock() {
    jdbc.queryForObject("SELECT id FROM T_GUILD_LOCK WHERE id = 1 FOR UPDATE", Integer.class);
  }

  /** The 21:00 run: one resident, unless an administrator already welcomed someone today. */
  @Transactional
  public String generateScheduled() {
    if (properties.enabled() && bornToday() > 0) return "今日はすでに住人を迎えています。";
    return generateToday();
  }

  /** Up to daily-limit residents a day (scheduled and manual together); failures get a few extra tries. */
  @Transactional
  public String generateToday() {
    if (!properties.enabled()) return "生成は無効です。GUILD_ENABLED を有効にしてください。";
    lock();
    LocalDate today = LocalDate.now(TOKYO);
    int limit = properties.dailyLimit();
    if (bornToday() >= limit) return "今日は上限の" + limit + "人を迎えました。明日また追加できます。";
    List<Integer> attempts = jdbc.queryForList("SELECT attempts FROM T_GUILD_JOB WHERE target_date = ?", Integer.class, today);
    int count = attempts.isEmpty() ? 0 : attempts.getFirst();
    if (count >= limit + FAILURE_ALLOWANCE) return "本日の試行上限（" + (limit + FAILURE_ALLOWANCE) + "回）に達しました。明日また実行できます。";
    if (attempts.isEmpty()) jdbc.update("INSERT INTO T_GUILD_JOB (target_date,status,attempts,message,model_id,version) VALUES (?, 'RUNNING',0,'生成中',?,'guild-v1')", today, ai.modelId());
    jdbc.update("UPDATE T_GUILD_JOB SET status = 'RUNNING', attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP WHERE target_date = ?", today);
    var choice = sources.choose(today);
    if (choice.source().isEmpty()) return finish(today, "SKIPPED", choice.skipped());
    var source = choice.source();
    String world = dossier.build(source.get());
    GuildCharacterGenerator.Draft draft;
    try {
      draft = generator.generate(source.get(), world, people.options());
    } catch (GuildCharacterGenerator.GenerationException failure) {
      // Only fixed text and the exception class name; never provider responses, prompts or source data.
      log.warn("Guild generation failed ({}, {})", failure.call() ? "bedrock call" : "invalid output", failure.getMessage());
      return finish(today, "FAILED", failure.call()
          ? "Bedrockの呼び出しに失敗しました（" + failure.getMessage() + "）。モデル設定・権限を確認して再試行してください。"
          : "AIの応答が形式に合いませんでした（" + failure.getMessage() + "）。もう一度試してください。");
    }
    String origin = GuildSourceService.GAMES.get(source.get().game())
        + " の記録を題材に生まれた創作人物。人物の暮らしや気質はフィクションで、特定のプレイヤーを表すものではありません。";
    if (nameTaken(draft.name())) {
      return finish(today, "FAILED", "AIが付けた名前が、既存の住人かプレイヤーの名前と重なりました。もう一度試してください。");
    }
    var look = people.look(draft.gender(), draft.occupation());
    jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance,age,gender,occupation,"
        + "name_lineage,outfit,hair_style,hair_color,skin_color,cloth_color,cloth2_color,home_village,current_village,faction,faith,"
        + "accessory,eye_color,eye_style,brow_style,outfit_variant) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        today, source.get().game(), draft.name(), draft.introduction(), origin, draft.personality(),
        List.of("ember", "moss", "violet").get(java.util.concurrent.ThreadLocalRandom.current().nextInt(3)),
        draft.age(), draft.gender(), draft.occupation(), draft.lineage().name().toLowerCase(), look.outfit(), look.hair(),
        look.hairColor(), look.skin(), look.cloth(), look.cloth2(), draft.village(), draft.village(), draft.faction(), draft.faith(), look.accessory(), look.eye(),
        look.eyeStyle(), look.browStyle(), look.outfitVariant());
    // Writes are serialized by lock(), so the newest id is this resident.
    Long id = jdbc.queryForObject("SELECT MAX(id) FROM M_GUILD_CHARACTER", Long.class);
    jdbc.update("INSERT INTO T_GUILD_SOURCE (character_id,source_key,facts,memory_label,category,reading,world_facts) VALUES (?,?,?,?,?,?,?)",
        id, source.get().key(), source.get().facts(), source.get().label(), source.get().category(), draft.reading(),
        world.isBlank() ? null : world);
    for (String[] kind : new String[][] {{"village", draft.village()}, {"faction", draft.faction()}, {"faith", draft.faith()}}) {
      jdbc.update("INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id,kind,to_value,change_kind,reason) VALUES (?,?,?,'birth',?)",
          id, kind[0], kind[1], "記憶から生まれたときに選ばれた");
    }
    // Build today's roster after the very first birth. A saved roster changes only by refreshVisits().
    ensureVisits(today);
    return finish(today, "SUCCEEDED", "「" + draft.name() + "」を迎えました（今日 " + bornToday() + " / " + limit
        + " 人）。今日の来店名簿に加えるには「来店者を引き直す」を押してください。");
  }

  /**
   * A resident's name must be new, and must not be (or contain, or sit inside) a player's name. The AI
   * never sees player names, so this is a backstop.
   */
  private boolean nameTaken(String name) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM M_GUILD_CHARACTER WHERE name = ?", Integer.class, name) > 0) return true;
    String wanted = name.toLowerCase(java.util.Locale.ROOT);
    List<String> players = jdbc.queryForList("SELECT player_name FROM M_PLAYER UNION SELECT player_name FROM M_CK_PLAYER", String.class);
    return players.stream().filter(java.util.Objects::nonNull).map(player -> player.strip().toLowerCase(java.util.Locale.ROOT))
        .filter(player -> player.length() >= 2).anyMatch(player -> player.contains(wanted) || wanted.contains(player));
  }

  /** Redraws today's visitors, e.g. so residents born today can come in. */
  @Transactional
  public String refreshVisits() {
    lock();
    LocalDate today = LocalDate.now(TOKYO);
    jdbc.update("DELETE FROM T_GUILD_VISIT WHERE visit_date = ?", today);
    jdbc.update("DELETE FROM T_GUILD_DAY WHERE visit_date = ?", today);
    ensureVisits(today);
    int count = jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_VISIT WHERE visit_date = ?", Integer.class, today);
    return count == 0 ? "まだ住人がいないため、来店者はいません。" : "今日の来店者を引き直しました（" + count + "人）。";
  }

  /** Changes only appearance columns, without invoking AI or rebuilding visits/talks/relationships. */
  @Transactional
  public String redrawAppearance(long id, boolean eyesOnly) {
    lock();
    Character resident = character(id);
    var look = people.redraw(resident.gender(), resident.occupation(), resident.look(), eyesOnly);
    if (eyesOnly && resident.look() != null) {
      jdbc.update("UPDATE M_GUILD_CHARACTER SET eye_style = ?, brow_style = ? WHERE id = ?", look.eyeStyle(), look.browStyle(), id);
    } else {
      jdbc.update("UPDATE M_GUILD_CHARACTER SET outfit = ?, hair_style = ?, hair_color = ?, skin_color = ?, "
          + "cloth_color = ?, cloth2_color = ?, accessory = ?, eye_color = ?, eye_style = ?, brow_style = ?, outfit_variant = ? WHERE id = ?",
          look.outfit(), look.hair(), look.hairColor(), look.skin(), look.cloth(), look.cloth2(), look.accessory(), look.eye(),
          look.eyeStyle(), look.browStyle(), look.outfitVariant(), id);
    }
    return "「" + resident.name() + "」の" + (eyesOnly && resident.look() != null ? "目元" : "外見") + "を調整しました。";
  }

  /**
   * Removes a resident with their visits, source, interactions (and what those interactions changed or
   * started), dungeon runs, talks and relationships. The source's memory may be used again.
   */
  @Transactional
  public String delete(long id) {
    lock();
    List<String> names = jdbc.queryForList("SELECT name FROM M_GUILD_CHARACTER WHERE id = ?", String.class, id);
    if (names.isEmpty()) return "その住人は見つかりませんでした。";
    List<Long> interactions = jdbc.queryForList("SELECT interaction_id FROM T_GUILD_INTERACTION_PARTICIPANT WHERE character_id = ?", Long.class, id);
    for (long interaction : interactions) {
      jdbc.update("DELETE FROM T_GUILD_THOUGHT WHERE interaction_id = ?", interaction);
      jdbc.update("DELETE FROM T_GUILD_AFFILIATION_HISTORY WHERE interaction_id = ?", interaction);
      jdbc.update("DELETE FROM T_GUILD_RELATION_EVENT WHERE interaction_id = ?", interaction);
      jdbc.update("DELETE FROM T_GUILD_THREAD WHERE origin_interaction_id = ? OR resolved_interaction_id = ?", interaction, interaction);
      jdbc.update("DELETE FROM T_GUILD_INTERACTION_PARTICIPANT WHERE interaction_id = ?", interaction);
      jdbc.update("DELETE FROM T_GUILD_INTERACTION WHERE id = ?", interaction);
    }
    // Dungeon runs they took part in go with them; their text names everyone in the party.
    for (long expedition : jdbc.queryForList("SELECT expedition_id FROM T_GUILD_EXPEDITION_MEMBER WHERE character_id = ?", Long.class, id)) {
      jdbc.update("DELETE FROM T_GUILD_RELATION_EVENT WHERE expedition_id = ?", expedition);
      jdbc.update("DELETE FROM T_GUILD_EXPEDITION_STAGE WHERE expedition_id = ?", expedition);
      jdbc.update("DELETE FROM T_GUILD_EXPEDITION_MEMBER WHERE expedition_id = ?", expedition);
      jdbc.update("DELETE FROM T_GUILD_EXPEDITION WHERE id = ?", expedition);
    }
    jdbc.update("DELETE FROM T_GUILD_TALK_CHOICE WHERE talk_id IN (SELECT id FROM T_GUILD_TALK WHERE character_id = ?)", id);
    jdbc.update("DELETE FROM T_GUILD_TALK WHERE character_id = ?", id);
    // Rumors they heard, or that are about them, of interactions they were not in.
    jdbc.update("DELETE FROM T_GUILD_THOUGHT WHERE character_id = ? OR about_id = ?", id, id);
    jdbc.update("DELETE FROM T_GUILD_AFFILIATION_HISTORY WHERE character_id = ?", id);
    jdbc.update("DELETE FROM T_GUILD_RELATION_EVENT WHERE from_id = ? OR to_id = ?", id, id);
    jdbc.update("DELETE FROM T_GUILD_THREAD WHERE a_id = ? OR b_id = ?", id, id);
    jdbc.update("DELETE FROM T_GUILD_RELATION WHERE from_id = ? OR to_id = ?", id, id);
    jdbc.update("DELETE FROM T_GUILD_BOND WHERE low_id = ? OR high_id = ? OR lead_id = ?", id, id, id);
    jdbc.update("DELETE FROM T_GUILD_VISIT WHERE character_id = ?", id);
    jdbc.update("DELETE FROM T_GUILD_SOURCE WHERE character_id = ?", id);
    jdbc.update("DELETE FROM M_GUILD_CHARACTER WHERE id = ?", id);
    return "「" + names.getFirst() + "」を削除しました。";
  }

  private String finish(LocalDate date, String status, String message) {
    jdbc.update("UPDATE T_GUILD_JOB SET status = ?, message = ?, updated_at = CURRENT_TIMESTAMP WHERE target_date = ?", status, message, date);
    return message;
  }

  @Transactional
  public void prepareToday() {
    lock();
    ensureVisits(LocalDate.now(TOKYO));
  }

  /** The chance that a visitor brings along someone they like (who may come there), in place of the last one in line. */
  static final double COMPANION_CHANCE = 0.5;
  /** How much a visitor must like someone to bring them along. */
  static final int COMPANION_AFFINITY = 5;

  /**
   * Today's visitors: ten for the guild from every resident (newest and longest away first), then five for each
   * nation's tavern from those who live there and are not at the guild. A resident is in one place a day. Now and then a
   * visitor comes with the one they like best, so people who get on meet again instead of waiting for the rotation.
   */
  private void ensureVisits(LocalDate today) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_DAY WHERE visit_date = ?", Integer.class, today) > 0) return;
    java.util.Map<GuildPlaces.Place, List<Long>> picked = new java.util.LinkedHashMap<>();
    List<Long> taken = new java.util.ArrayList<>();
    for (GuildPlaces.Place place : GuildPlaces.ALL) {
      List<Object> args = new java.util.ArrayList<>();
      String where = "c.status = 'ALIVE'";
      if (!place.guild()) {
        where += " AND c.current_village = ?";
        args.add(place.nation());
      }
      // Ids come from the database, never from a request.
      if (!taken.isEmpty()) where += " AND c.id NOT IN (" + String.join(",", taken.stream().map(String::valueOf).toList()) + ")";
      args.add(today.minusDays(1));
      List<Long> ids = jdbc.queryForList("SELECT c.id FROM M_GUILD_CHARACTER c LEFT JOIN T_GUILD_VISIT v ON c.id = v.character_id WHERE "
          + where + " GROUP BY c.id, c.born_on ORDER BY CASE WHEN c.born_on >= ? THEN 0 ELSE 1 END, "
          + "COALESCE(MAX(v.visit_date), DATE '1970-01-01'), RANDOM() LIMIT " + place.seats(), Long.class, args.toArray());
      ids = companions(ids, place, taken);
      picked.put(place, ids);
      taken.addAll(ids);
    }
    // An empty database should welcome its very first birth immediately.
    if (taken.isEmpty()) return;
    jdbc.update("INSERT INTO T_GUILD_DAY VALUES (?)", today);
    picked.forEach((place, ids) -> {
      for (int i = 0; i < ids.size(); i++) {
        int seat = place.base() + i;
        jdbc.update("INSERT INTO T_GUILD_VISIT (visit_date,character_id,seat,place,morning,afternoon,evening) VALUES (?,?,?,?,?,?,?)",
            today, ids.get(i), seat, place.key(), place.line(seat, "morning"), place.line(seat, "afternoon"), place.line(seat, "evening"));
      }
    });
  }

  /** The line for one place, where each one in turn may bring a companion, who takes the seat of the last one in line. */
  private List<Long> companions(List<Long> line, GuildPlaces.Place place, List<Long> taken) {
    List<Long> seated = new java.util.ArrayList<>();
    java.util.Deque<Long> waiting = new java.util.ArrayDeque<>(line);
    var random = java.util.concurrent.ThreadLocalRandom.current();
    while (!waiting.isEmpty() && seated.size() < place.seats()) {
      long visitor = waiting.pollFirst();
      seated.add(visitor);
      if (seated.size() >= place.seats() || random.nextDouble() >= COMPANION_CHANCE) continue;
      List<Object> args = new java.util.ArrayList<>(List.of(visitor, COMPANION_AFFINITY));
      String where = "";
      if (!place.guild()) {
        where = " AND c.current_village = ?";
        args.add(place.nation());
      }
      List<Long> away = new java.util.ArrayList<>(taken);
      away.addAll(seated);
      // Ids come from the database, never from a request.
      where += " AND c.id NOT IN (" + String.join(",", away.stream().map(String::valueOf).toList()) + ")";
      // One of the three they like best, so it is not always the same one.
      List<Long> liked = jdbc.queryForList("SELECT c.id FROM T_GUILD_RELATION r JOIN M_GUILD_CHARACTER c ON c.id = r.to_id WHERE r.from_id = ? "
          + "AND r.affinity >= ? AND c.status = 'ALIVE'" + where + " ORDER BY r.affinity DESC, c.id LIMIT 3", Long.class, args.toArray());
      if (liked.isEmpty()) continue;
      long friend = liked.get(random.nextInt(liked.size()));
      waiting.remove(friend);
      if (waiting.size() + seated.size() >= place.seats()) waiting.pollLast();
      waiting.addFirst(friend);
    }
    return seated;
  }
}
