package com.yuki.rubinco.guild;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Where a resident's mind is now (V69): what is on their mind and how they see someone, as the AI wrote it after their
 * latest interactions. The newest row is the current one. Later scenes and talks are given it, with what the two
 * talked about before, so a conversation can pick up where the last one left off.
 */
@Service
@RequiredArgsConstructor
public class GuildThoughts {
  /** How many of the pair's earlier interactions a scene is reminded of. */
  static final int EARLIER = 2;

  private final JdbcTemplate jdbc;

  /** One thought, dated by the day of the interaction it came from. */
  public record Entry(LocalDate day, String text) { }

  /** What is on their mind now, or null. */
  public String now(long characterId) {
    return jdbc.queryForList("SELECT text FROM T_GUILD_THOUGHT WHERE character_id = ? AND about_id IS NULL ORDER BY id DESC LIMIT 1",
        String.class, characterId).stream().findFirst().orElse(null);
  }

  /** How they see someone now, or null. */
  public String about(long characterId, long otherId) {
    return jdbc.queryForList("SELECT text FROM T_GUILD_THOUGHT WHERE character_id = ? AND about_id = ? ORDER BY id DESC LIMIT 1",
        String.class, characterId, otherId).stream().findFirst().orElse(null);
  }

  /** How they see each one they have thoughts about now, by that one's id. */
  public Map<Long, String> abouts(long characterId) {
    Map<Long, String> out = new LinkedHashMap<>();
    jdbc.query("""
        SELECT t.about_id, t.text FROM T_GUILD_THOUGHT t WHERE t.character_id = ? AND t.about_id IS NOT NULL
          AND t.id = (SELECT MAX(u.id) FROM T_GUILD_THOUGHT u WHERE u.character_id = t.character_id AND u.about_id = t.about_id)
        """, rs -> { out.put(rs.getLong(1), rs.getString(2)); }, characterId);
    return out;
  }

  /** What was on their mind, newest first: the first is where they are now, the rest how they got there. */
  public List<Entry> history(long characterId, int limit) {
    return jdbc.query("""
        SELECT t.text, i.happened_at FROM T_GUILD_THOUGHT t JOIN T_GUILD_INTERACTION i ON i.id = t.interaction_id
        WHERE t.character_id = ? AND t.about_id IS NULL ORDER BY t.id DESC LIMIT ?
        """, (rs, row) -> new Entry(rs.getObject("happened_at", java.time.OffsetDateTime.class)
        .atZoneSameInstant(GuildService.TOKYO).toLocalDate(), rs.getString("text")), characterId, Math.max(1, Math.min(limit, 20)));
  }

  /**
   * The lines a scene between a and b is given: each one's mind now, how each sees the other, and what the two talked
   * about in their last few interactions. Empty when there is nothing yet.
   */
  public String recall(GuildInteractionService.Person a, GuildInteractionService.Person b) {
    List<String> lines = new ArrayList<>();
    for (var pair : List.of(List.of(a, b), List.of(b, a))) {
      var self = pair.get(0);
      var other = pair.get(1);
      String now = now(self.id());
      if (now != null) lines.add(self.name() + "が今思っていること=" + now);
      String about = about(self.id(), other.id());
      if (about != null) lines.add(self.name() + "が" + other.name() + "について思っていること=" + about);
    }
    var earlier = jdbc.query("""
        SELECT i.summary, i.happened_at FROM T_GUILD_INTERACTION i
        WHERE EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)
          AND EXISTS (SELECT 1 FROM T_GUILD_INTERACTION_PARTICIPANT p WHERE p.interaction_id = i.id AND p.character_id = ?)
        ORDER BY i.happened_at DESC, i.id DESC LIMIT ?
        """, (rs, row) -> rs.getObject("happened_at", java.time.OffsetDateTime.class).atZoneSameInstant(GuildService.TOKYO)
        .toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("M月d日")) + "：" + rs.getString("summary"), a.id(), b.id(), EARLIER);
    if (!earlier.isEmpty()) lines.add("前に二人で話したこと=" + String.join("／", earlier));
    return String.join("\n", lines);
  }

  /** Keeps the thoughts written after an interaction; a name that is neither of the two is skipped. */
  public void save(long interactionId, GuildInteractionService.Person a, GuildInteractionService.Person b,
                   List<GuildInteractionWriter.Thought> thoughts) {
    for (var thought : thoughts) {
      var self = thought.name().equals(a.name()) ? a : thought.name().equals(b.name()) ? b : null;
      if (self == null) continue;
      var other = self == a ? b : a;
      if (thought.now() != null) {
        jdbc.update("INSERT INTO T_GUILD_THOUGHT (character_id,about_id,interaction_id,text) VALUES (?,NULL,?,?)", self.id(), interactionId, thought.now());
      }
      if (thought.about() != null) {
        jdbc.update("INSERT INTO T_GUILD_THOUGHT (character_id,about_id,interaction_id,text) VALUES (?,?,?,?)", self.id(), other.id(),
            interactionId, thought.about());
      }
    }
  }
}
