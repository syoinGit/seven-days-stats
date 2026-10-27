package com.yuki.rubinco.guild;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The town talks, and feelings fade. Big news between two residents (a new couple, a falling-out, someone joining a
 * faction) reaches a few of their friends as a rumor, which becomes what is on the hearers' mind (a thought of kind
 * 'rumor'). Someone with a partner who grows close to another may be heard of by that partner, who takes it badly.
 * Two who have not met for a while cool a little, a step at a time. All of it is code; no AI is asked.
 */
@Service
@RequiredArgsConstructor
public class GuildRumors {
  /** How many friends of the two hear a rumor at most, and how fond of one of them a hearer must be. */
  static final int HEARERS = 3;
  static final int HEARER_AFFINITY = 10;
  /** A partner hears of their lover growing close to another this often. */
  static final double JEALOUS_CHANCE = 0.5;
  /** What the partner feels toward the other one (wariness up, affinity down) and toward their own lover (trust down). */
  static final int JEALOUS_WARINESS = 9;
  static final int JEALOUS_AFFINITY = 6;
  static final int JEALOUS_TRUST = 3;
  /** Without meeting (or anything else changing it) for this many days, affinity above the floor drops by DRIFT; again after as many more. */
  static final int DRIFT_AFTER_DAYS = 10;
  static final int DRIFT = 3;
  static final int DRIFT_FLOOR = 10;

  private final JdbcTemplate jdbc;

  /**
   * Feelings that are not fed fade: affinity above DRIFT_FLOOR drops by DRIFT for each one not touched in DRIFT_AFTER_DAYS,
   * except between spouses. Touching the row marks the step, so running this more often does nothing more. Returns
   * how many cooled.
   */
  public int drift(OffsetDateTime now) {
    return jdbc.update("""
        UPDATE T_GUILD_RELATION r SET affinity = GREATEST(?, r.affinity - ?), updated_at = ?
        WHERE r.affinity > ? AND r.updated_at < ?
          AND NOT EXISTS (SELECT 1 FROM T_GUILD_BOND b WHERE b.label = 'spouse'
            AND b.low_id = LEAST(r.from_id, r.to_id) AND b.high_id = GREATEST(r.from_id, r.to_id))
        """, DRIFT_FLOOR, DRIFT, now, DRIFT_FLOOR, now.minusDays(DRIFT_AFTER_DAYS));
  }

  /** News of a and b reaches up to HEARERS living residents fond of either of them (not a, b or anyone in skip). */
  public List<Long> spread(long interactionId, long a, long b, String news, List<Long> skip) {
    List<Long> away = new java.util.ArrayList<>(List.of(a, b));
    away.addAll(skip);
    // Ids come from the database, never from a request.
    String not = String.join(",", away.stream().map(String::valueOf).toList());
    List<Long> fond = new java.util.ArrayList<>(jdbc.queryForList("SELECT DISTINCT r.from_id FROM T_GUILD_RELATION r JOIN M_GUILD_CHARACTER c ON c.id = r.from_id "
        + "WHERE r.to_id IN (?, ?) AND r.affinity >= ? AND c.status = 'ALIVE' AND r.from_id NOT IN (" + not + ")", Long.class, a, b, HEARER_AFFINITY));
    java.util.Collections.shuffle(fond, ThreadLocalRandom.current());
    List<Long> hearers = fond.subList(0, Math.min(HEARERS, fond.size()));
    for (long hearer : hearers) think(hearer, null, interactionId, news);
    return List.copyOf(hearers);
  }

  /**
   * partner hears that their lover grew close to other: it weighs on them (a rumor on their mind, and how they see the other
   * one), they grow wary of the other and trust their lover a little less. Logged with the interaction.
   */
  public void jealous(long interactionId, long partner, String partnerName, long lover, String loverName, long other, String otherName) {
    String news = loverName + "が" + otherName + "と親しくしているらしい。";
    think(partner, null, interactionId, news + "胸がざわつく。");
    think(partner, other, interactionId, otherName + "が" + loverName + "に近づいているのが気に入らない。");
    feel(interactionId, partner, other, 0, -JEALOUS_AFFINITY, JEALOUS_WARINESS, "噂：" + news);
    feel(interactionId, partner, lover, -JEALOUS_TRUST, 0, 0, "噂：" + news);
  }

  /** Whether partner hears of it this time. */
  static boolean heard() {
    return ThreadLocalRandom.current().nextDouble() < JEALOUS_CHANCE;
  }

  private void think(long character, Long about, long interactionId, String text) {
    jdbc.update("INSERT INTO T_GUILD_THOUGHT (character_id,about_id,interaction_id,text,kind) VALUES (?,?,?,?,'rumor')",
        character, about, interactionId, text.length() > 200 ? text.substring(0, 199) + "…" : text);
  }

  private void feel(long interactionId, long from, long to, int trust, int affinity, int wariness, String reason) {
    var rel = jdbc.query("SELECT trust, affinity, wariness FROM T_GUILD_RELATION WHERE from_id = ? AND to_id = ?",
        (rs, row) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)}, from, to).stream().findFirst().orElse(null);
    int newTrust = Math.clamp((rel == null ? 0 : rel[0]) + trust, -100, 100);
    int newAffinity = Math.clamp((rel == null ? 0 : rel[1]) + affinity, -100, 100);
    int newWariness = Math.clamp((rel == null ? 0 : rel[2]) + wariness, 0, 100);
    // updated_at stays: hearing of someone is not meeting them, so it does not hold off their drifting apart.
    if (rel == null) {
      jdbc.update("INSERT INTO T_GUILD_RELATION (from_id,to_id,trust,affinity,wariness,debt) VALUES (?,?,?,?,?,0)", from, to, newTrust, newAffinity, newWariness);
    } else {
      jdbc.update("UPDATE T_GUILD_RELATION SET trust = ?, affinity = ?, wariness = ? WHERE from_id = ? AND to_id = ?", newTrust, newAffinity, newWariness, from, to);
    }
    jdbc.update("INSERT INTO T_GUILD_RELATION_EVENT (interaction_id,from_id,to_id,change_kind,trust_delta,affinity_delta,wariness_delta,debt_delta,reason) "
        + "VALUES (?,?,?,'axes',?,?,?,0,?)", interactionId, from, to, newTrust - (rel == null ? 0 : rel[0]),
        newAffinity - (rel == null ? 0 : rel[1]), newWariness - (rel == null ? 0 : rel[2]), reason.length() > 200 ? reason.substring(0, 199) + "…" : reason);
  }
}
