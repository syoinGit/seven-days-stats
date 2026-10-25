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
public class GuildService {
  public static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
  private final JdbcTemplate jdbc;
  private final GuildProperties properties;
  private final AiAnalysisProperties ai;
  private final GuildSourceService sources;
  private final GuildCharacterGenerator generator;

  public record Character(long id, LocalDate bornOn, String game, String name, String introduction,
                          String origin, String personality, String appearance, String status) { }
  public record Visit(Character character, int seat, String line) { }
  public record Room(LocalDate date, String phase, List<Visit> visits) { }
  public record Job(LocalDate date, String status, int attempts, String message) { }

  private static final String PUBLIC_COLUMNS = "id, born_on, game_id, name, introduction, origin, personality, appearance, status";
  private static Character character(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Character(rs.getLong("id"), rs.getDate("born_on").toLocalDate(), rs.getString("game_id"),
        rs.getString("name"), rs.getString("introduction"), rs.getString("origin"),
        rs.getString("personality"), rs.getString("appearance"), rs.getString("status"));
  }

  public List<Character> characters(int page) {
    return jdbc.query("SELECT " + PUBLIC_COLUMNS + " FROM M_GUILD_CHARACTER ORDER BY born_on DESC LIMIT 40 OFFSET ?",
        (rs, row) -> character(rs), Math.max(0, Math.min(page, 10000)) * 40);
  }

  public Character character(long id) {
    return jdbc.query("SELECT " + PUBLIC_COLUMNS + " FROM M_GUILD_CHARACTER WHERE id = ?",
        (rs, row) -> character(rs), id).stream().findFirst().orElseThrow(() ->
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
  }

  public Room room(ZonedDateTime now) {
    var local = now.withZoneSameInstant(TOKYO);
    String phase = local.getHour() < 11 ? "morning" : local.getHour() < 18 ? "afternoon" : "evening";
    // Keep the last established scene readable after downtime, clearly labelled with its date.
    LocalDate date = jdbc.queryForObject("SELECT MAX(visit_date) FROM T_GUILD_DAY WHERE visit_date <= ?", LocalDate.class, local.toLocalDate());
    if (date == null) return new Room(local.toLocalDate(), phase, List.of());
    List<Visit> visits = jdbc.query("SELECT c.*, v.seat, v." + phase + " AS line FROM T_GUILD_VISIT v "
        + "JOIN M_GUILD_CHARACTER c ON c.id = v.character_id WHERE v.visit_date = ? AND c.status = 'ALIVE' ORDER BY v.seat",
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

  @Transactional
  public String generateToday() {
    if (!properties.enabled()) return "生成は無効です。GUILD_ENABLED を有効にしてください。";
    lock();
    LocalDate today = LocalDate.now(TOKYO);
    if (jdbc.queryForObject("SELECT COUNT(*) FROM M_GUILD_CHARACTER WHERE born_on = ?", Integer.class, today) > 0) {
      return "今日はすでに一人を迎えています。";
    }
    List<Integer> attempts = jdbc.queryForList("SELECT attempts FROM T_GUILD_JOB WHERE target_date = ?", Integer.class, today);
    int count = attempts.isEmpty() ? 0 : attempts.getFirst();
    if (count >= 3) return "本日の試行上限（3回）に達しました。明日また実行できます。";
    if (attempts.isEmpty()) jdbc.update("INSERT INTO T_GUILD_JOB (target_date,status,attempts,message,model_id,version) VALUES (?, 'RUNNING',0,'生成中',?,'guild-v1')", today, ai.modelId());
    jdbc.update("UPDATE T_GUILD_JOB SET status = 'RUNNING', attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP WHERE target_date = ?", today);
    var source = sources.choose(today);
    if (source.isEmpty()) return finish(today, "SKIPPED", "未使用の確定済み記録がありません。人物の追加を見送りました。");
    GuildCharacterGenerator.Draft draft;
    try {
      draft = generator.generate(source.get());
    } catch (RuntimeException failure) {
      // Do not persist/log provider responses, prompts or exception text containing source data.
      return finish(today, "FAILED", "人物生成に失敗しました。Bedrock設定・権限を確認して再試行してください。");
    }
    String origin = switch (source.get().game()) {
      case "7dtd" -> "7 Days to Die のある一日の接続記録を題材に生まれた創作人物。人物の暮らしや気質はフィクションです。";
      case "terraria" -> "Terraria の保存された世界を題材に生まれた創作人物。特定プレイヤーの功績を表すものではありません。";
      default -> source.get().key().startsWith("world:")
          ? "Core Keeper の保存された世界を題材に生まれた創作人物。特定プレイヤーの功績を表すものではありません。"
          : "Core Keeper のある一日の接続記録を題材に生まれた創作人物。人物の暮らしや気質はフィクションです。";
    };
    jdbc.update("INSERT INTO M_GUILD_CHARACTER (born_on,game_id,name,introduction,origin,personality,appearance) VALUES (?,?,?,?,?,?,?)",
        today, source.get().game(), draft.name(), draft.introduction(), origin, draft.personality(), draft.appearance());
    Long id = jdbc.queryForObject("SELECT id FROM M_GUILD_CHARACTER WHERE born_on = ?", Long.class, today);
    jdbc.update("INSERT INTO T_GUILD_SOURCE (character_id,source_key,facts) VALUES (?,?,?)", id, source.get().key(), source.get().facts());
    // Build today's fixed roster after the birth if none exists. Never redraw a saved roster.
    ensureVisits(today);
    return finish(today, "SUCCEEDED", "新しい住人を一人迎えました。来店名簿が確定済みの場合は、翌日以降に来店します。");
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
