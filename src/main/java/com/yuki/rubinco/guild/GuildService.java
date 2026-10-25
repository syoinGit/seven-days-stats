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

  /** memoryLabel and memory are null for residents born before memories were recorded. */
  public record Character(long id, LocalDate bornOn, String game, String name, String introduction,
                          String origin, String personality, String appearance, String status,
                          Integer age, String gender, String occupation, String memoryLabel, String memory) {
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

    public List<String> memoryLines() {
      return memoryLabel == null || memory == null ? List.of() : memory.lines().toList();
    }
  }
  public record Visit(Character character, int seat, String line) { }
  public record Room(LocalDate date, String phase, List<Visit> visits) { }
  public record Job(LocalDate date, String status, int attempts, String message) { }

  // The source key (it names the player and day) stays private; the memory label and lines are public.
  private static final String PUBLIC_COLUMNS = "c.id, c.born_on, c.game_id, c.name, c.introduction, c.origin, "
      + "c.personality, c.appearance, c.status, c.age, c.gender, c.occupation, s.memory_label, "
      + "CASE WHEN s.memory_label IS NULL THEN NULL ELSE s.facts END AS memory";
  private static final String FROM = " FROM M_GUILD_CHARACTER c LEFT JOIN T_GUILD_SOURCE s ON s.character_id = c.id";
  private static Character character(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Character(rs.getLong("id"), rs.getDate("born_on").toLocalDate(), rs.getString("game_id"),
        rs.getString("name"), rs.getString("introduction"), rs.getString("origin"),
        rs.getString("personality"), rs.getString("appearance"), rs.getString("status"),
        (Integer) rs.getObject("age"), rs.getString("gender"), rs.getString("occupation"),
        rs.getString("memory_label"), rs.getString("memory"));
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

  public Room room(ZonedDateTime now) {
    var local = now.withZoneSameInstant(TOKYO);
    String phase = local.getHour() < 11 ? "morning" : local.getHour() < 18 ? "afternoon" : "evening";
    // Keep the last established scene readable after downtime, clearly labelled with its date.
    LocalDate date = jdbc.queryForObject("SELECT MAX(visit_date) FROM T_GUILD_DAY WHERE visit_date <= ?", LocalDate.class, local.toLocalDate());
    if (date == null) return new Room(local.toLocalDate(), phase, List.of());
    List<Visit> visits = jdbc.query("SELECT " + PUBLIC_COLUMNS + ", v.seat, v." + phase + " AS line" + FROM
        + " JOIN T_GUILD_VISIT v ON c.id = v.character_id WHERE v.visit_date = ? AND c.status = 'ALIVE' ORDER BY v.seat",
        (rs, row) -> new Visit(character(rs), rs.getInt("seat"), rs.getString("line")), date);
    return new Room(date, phase, visits);
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
    var source = sources.choose(today);
    if (source.isEmpty()) return finish(today, "SKIPPED", "まだ使っていない過去の記録がありません。人物の追加を見送りました。");
    GuildCharacterGenerator.Draft draft;
    try {
      draft = generator.generate(source.get());
    } catch (GuildCharacterGenerator.GenerationException failure) {
      // Only fixed text and the exception class name; never provider responses, prompts or source data.
      log.warn("Guild generation failed ({}, {})", failure.call() ? "bedrock call" : "invalid output", failure.getMessage());
      return finish(today, "FAILED", failure.call()
          ? "Bedrockの呼び出しに失敗しました（" + failure.getMessage() + "）。モデル設定・権限を確認して再試行してください。"
          : "AIの応答が形式に合いませんでした（" + failure.getMessage() + "）。もう一度試してください。");
    }
    String origin = GuildSourceService.GAMES.get(source.get().game())
        + " の記録を題材に生まれた創作人物。人物の暮らしや気質はフィクションで、特定のプレイヤーを表すものではありません。";
    jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance,age,gender,occupation) "
        + "VALUES (?,?,?,?,?,?,?,?,?,?)", today, source.get().game(), draft.name(), draft.introduction(), origin,
        draft.personality(), draft.appearance(), draft.age(), draft.gender(), draft.occupation());
    // Writes are serialized by lock(), so the newest id is this resident.
    Long id = jdbc.queryForObject("SELECT MAX(id) FROM M_GUILD_CHARACTER", Long.class);
    jdbc.update("INSERT INTO T_GUILD_SOURCE (character_id,source_key,facts,memory_label) VALUES (?,?,?,?)",
        id, source.get().key(), source.get().facts(), source.get().label());
    // Build today's roster after the very first birth. A saved roster changes only by refreshVisits().
    ensureVisits(today);
    return finish(today, "SUCCEEDED", "「" + draft.name() + "」を迎えました（今日 " + bornToday() + " / " + limit
        + " 人）。今日の来店名簿に加えるには「来店者を引き直す」を押してください。");
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

  /** Removes a resident with their visits and source; the source's memory may be used again. */
  @Transactional
  public String delete(long id) {
    lock();
    List<String> names = jdbc.queryForList("SELECT name FROM M_GUILD_CHARACTER WHERE id = ?", String.class, id);
    if (names.isEmpty()) return "その住人は見つかりませんでした。";
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

  private void ensureVisits(LocalDate today) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_DAY WHERE visit_date = ?", Integer.class, today) > 0) return;
    List<Long> ids = jdbc.queryForList("""
        SELECT c.id FROM M_GUILD_CHARACTER c LEFT JOIN T_GUILD_VISIT v ON c.id = v.character_id
        WHERE c.status = 'ALIVE' GROUP BY c.id, c.born_on
        ORDER BY CASE WHEN c.born_on >= ? THEN 0 ELSE 1 END,
          COALESCE(MAX(v.visit_date), DATE '1970-01-01'), RANDOM() LIMIT 5
        """, Long.class, today.minusDays(1));
    // An empty database should welcome its very first birth immediately.
    if (ids.isEmpty()) return;
    jdbc.update("INSERT INTO T_GUILD_DAY VALUES (?)", today);
    for (int seat = 0; seat < ids.size(); seat++) {
      jdbc.update("INSERT INTO T_GUILD_VISIT (visit_date,character_id,seat,morning,afternoon,evening) VALUES (?,?,?,?,?,?)",
          today, ids.get(seat), seat, MORNING[seat], AFTERNOON[seat], EVENING[seat]);
    }
  }

  private static final String[] MORNING = {"窓辺で朝のパンを食べている。", "暖炉のそばで旅装を整えている。", "卓の上で今日の予定を考えている。", "静かに手を合わせている。", "入り口で靴の泥を落としている。"};
  private static final String[] AFTERNOON = {"卓で道具の手入れをしている。", "掲示板の依頼を眺めている。", "昼食のスープをゆっくり味わっている。", "記録棚で本をめくっている。", "カウンターで休憩している。"};
  private static final String[] EVENING = {"暖炉を眺めながら温かい飲み物を飲んでいる。", "カウンターで夕食を待っている。", "卓で明日の支度をしている。", "本を閉じ、静かに休んでいる。", "窓の外に灯る明かりを眺めている。"};
}
