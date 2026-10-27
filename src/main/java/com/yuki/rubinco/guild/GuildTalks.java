package com.yuki.rubinco.guild;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Talks with a resident (V67). Each evening the AI writes one talk for a resident who is in some place that day
 * (origin "schedule"), and administrators may have it write one more a day (origin "manual"). A visitor talks with
 * a resident through that resident's newest talk: they read the first line and pick one of three answers. Which
 * answer they picked, and that they talked today, stays in their browser; nothing about visitors is stored.
 */
@Service
@lombok.extern.slf4j.Slf4j
public class GuildTalks {
  public static final String SCHEDULE = "schedule";
  public static final String MANUAL = "manual";
  /** Failed tries a scheduled day gets (kept in memory), so one failed call doesn't lose the evening. */
  static final int SCHEDULE_TRIES = 3;

  private final JdbcTemplate jdbc;
  private final GuildService guild;
  private final GuildVoice voices;
  private final GuildTalkWriter writer;
  private final GuildThoughts thoughts;
  private final TransactionTemplate transactions;
  private final Map<LocalDate, Integer> tries = new ConcurrentHashMap<>();

  public GuildTalks(JdbcTemplate jdbc, GuildService guild, GuildVoice voices, GuildTalkWriter writer, GuildThoughts thoughts,
                    TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.guild = guild;
    this.voices = voices;
    this.writer = writer;
    this.thoughts = thoughts;
    this.transactions = transactions;
  }

  /** labels: the visitor's three answers; the replies are read one at a time with reply(). */
  public record Talk(long id, long characterId, LocalDate date, String place, String opening, List<String> labels) { }

  /** One written talk, for the maintenance page. */
  public record Row(long id, LocalDate date, String origin, String name, String place, String opening, OffsetDateTime createdAt) {
    public String originName() {
      return SCHEDULE.equals(origin) ? "定時" : "メンテ";
    }

    public String placeName() {
      var known = GuildPlaces.of(place);
      return known == null ? place : known.title();
    }
  }

  /** The evening's talk, tried a few times; only while the day's scheduled talk is not written yet. */
  public String writeScheduled(ZonedDateTime now) {
    var date = now.withZoneSameInstant(GuildService.TOKYO).toLocalDate();
    if (written(date, SCHEDULE)) return "今日の会話はすでに作られています。";
    tries.keySet().removeIf(day -> !day.equals(date));
    if (tries.getOrDefault(date, 0) >= SCHEDULE_TRIES) return "今日の会話の作成は試行回数の上限に達しました。";
    String result = write(now, SCHEDULE);
    if (!written(date, SCHEDULE)) tries.merge(date, 1, Integer::sum);
    return result;
  }

  /** For tests: the scheduled tries are kept in memory only. */
  void forgetTries() {
    tries.clear();
  }

  /**
   * Has the AI write one talk for a resident drawn at random from those in some place today (not one who already
   * got a talk today). At most one per origin a day; the AI is called outside any transaction.
   */
  public String write(ZonedDateTime now, String origin) {
    if (!Set.of(SCHEDULE, MANUAL).contains(origin)) throw new IllegalArgumentException("origin");
    var date = now.withZoneSameInstant(GuildService.TOKYO).toLocalDate();
    if (written(date, origin)) return MANUAL.equals(origin) ? "メンテからの会話は今日すでに作りました（1日1回まで）。" : "今日の会話はすでに作られています。";
    var drawn = jdbc.query("""
        SELECT v.character_id, v.place FROM T_GUILD_VISIT v JOIN M_GUILD_CHARACTER c ON c.id = v.character_id
        WHERE v.visit_date = ? AND c.status = 'ALIVE'
          AND NOT EXISTS (SELECT 1 FROM T_GUILD_TALK t WHERE t.talk_date = ? AND t.character_id = c.id)
        ORDER BY RANDOM() LIMIT 1
        """, (rs, row) -> new Object[] {rs.getLong(1), rs.getString(2)}, date, date);
    if (drawn.isEmpty()) return "今日どこかの酒場にいる住人がいないため、会話を作れませんでした。";
    long id = (Long) drawn.getFirst()[0];
    String place = (String) drawn.getFirst()[1];
    var character = guild.character(id);
    var others = jdbc.queryForList("SELECT name FROM M_GUILD_CHARACTER WHERE id <> ?", String.class, id);
    GuildTalkWriter.Talk talk;
    try {
      talk = writer.write(new GuildTalkWriter.Speaker(character.name(), character.profile(), character.personality(), character.introduction(),
          voices.of(character).describe(), character.village(), character.faction(), character.faith(), GuildPlaces.of(place).title(), others,
          thoughts.now(id)));
    } catch (GuildTalkWriter.WritingException failure) {
      log.warn("Guild talk writing failed ({}, {})", failure.call() ? "bedrock call" : "invalid output", failure.getMessage());
      return "AIが会話を書けませんでした（" + (failure.call() ? "呼び出しの失敗" : "応答が条件を満たさない") + "）。";
    }
    try {
      transactions.executeWithoutResult(status -> {
        jdbc.update("INSERT INTO T_GUILD_TALK (talk_date,origin,character_id,place,opening) VALUES (?,?,?,?,?)", date, origin, id, place, talk.opening());
        long talkId = jdbc.queryForObject("SELECT id FROM T_GUILD_TALK WHERE talk_date = ? AND origin = ?", Long.class, date, origin);
        for (int i = 0; i < talk.choices().size(); i++) {
          jdbc.update("INSERT INTO T_GUILD_TALK_CHOICE (talk_id,position,label,reply) VALUES (?,?,?,?)",
              talkId, i, talk.choices().get(i).label(), talk.choices().get(i).reply());
        }
      });
    } catch (DuplicateKeyException race) {
      return "今日の会話はすでに作られています。";
    } catch (org.springframework.dao.DataIntegrityViolationException gone) {
      // The resident was deleted while the AI was writing.
      return "住人が見つからなくなったため、会話を保存しませんでした。";
    }
    return "「" + character.name() + "」との会話を作りました（" + GuildPlaces.of(place).title() + "）。";
  }

  public boolean written(LocalDate date, String origin) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM T_GUILD_TALK WHERE talk_date = ? AND origin = ?", Integer.class, date, origin) > 0;
  }

  /** The resident's newest talk, or null; only for the living. */
  public Talk latest(long characterId) {
    var talks = jdbc.query("""
        SELECT t.id, t.talk_date, t.place, t.opening FROM T_GUILD_TALK t JOIN M_GUILD_CHARACTER c ON c.id = t.character_id
        WHERE t.character_id = ? AND c.status = 'ALIVE' ORDER BY t.id DESC LIMIT 1
        """, (rs, row) -> new Talk(rs.getLong("id"), characterId, rs.getObject("talk_date", LocalDate.class), rs.getString("place"),
        rs.getString("opening"), List.of()), characterId);
    if (talks.isEmpty()) return null;
    var talk = talks.getFirst();
    var labels = jdbc.queryForList("SELECT label FROM T_GUILD_TALK_CHOICE WHERE talk_id = ? ORDER BY position", String.class, talk.id());
    return new Talk(talk.id(), characterId, talk.date(), talk.place(), talk.opening(), List.copyOf(labels));
  }

  /** The resident's reply to one answer of a talk, or null when there is no such talk (or its resident is gone). */
  public String reply(long talkId, int position) {
    return jdbc.queryForList("""
        SELECT ch.reply FROM T_GUILD_TALK_CHOICE ch JOIN T_GUILD_TALK t ON t.id = ch.talk_id JOIN M_GUILD_CHARACTER c ON c.id = t.character_id
        WHERE ch.talk_id = ? AND ch.position = ? AND c.status = 'ALIVE'
        """, String.class, talkId, position).stream().findFirst().orElse(null);
  }


  /**
   * Who sits beside the traveller at a place: a living resident with a talk, at random, not one of skip (those the
   * visitor already talked with today, as their browser says). Those whose newest talk was written at this place come
   * first; anyone else otherwise. Null when nobody is left.
   */
  public Long draw(Collection<Long> skip, String place) {
    var pool = jdbc.query("""
        SELECT t.character_id, t.place FROM T_GUILD_TALK t JOIN M_GUILD_CHARACTER c ON c.id = t.character_id
        WHERE c.status = 'ALIVE' AND t.id = (SELECT MAX(u.id) FROM T_GUILD_TALK u WHERE u.character_id = t.character_id)
        """, (rs, row) -> Map.entry(rs.getLong(1), rs.getString(2))).stream().filter(entry -> !skip.contains(entry.getKey())).toList();
    var here = pool.stream().filter(entry -> entry.getValue().equals(place)).toList();
    var from = here.isEmpty() ? pool : here;
    if (from.isEmpty()) return null;
    return from.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(from.size())).getKey();
  }

  public List<Row> recent(int limit) {
    return jdbc.query("""
        SELECT t.id, t.talk_date, t.origin, c.name, t.place, t.opening, t.created_at FROM T_GUILD_TALK t
        JOIN M_GUILD_CHARACTER c ON c.id = t.character_id ORDER BY t.id DESC LIMIT ?
        """, (rs, row) -> new Row(rs.getLong("id"), rs.getObject("talk_date", LocalDate.class), rs.getString("origin"), rs.getString("name"),
        rs.getString("place"), rs.getString("opening"), rs.getObject("created_at", OffsetDateTime.class)), limit);
  }
}
