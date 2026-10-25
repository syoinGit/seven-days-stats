package com.yuki.rubinco.guild;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The world's villages, factions and faiths (master tables from V58) and the few rules that tie them
 * together: a faction is seated in a village, Tsukikage is a Japanese-style settlement, some
 * occupations belong to one village. Residents' affiliations are always keys from these tables.
 */
@Component
@RequiredArgsConstructor
public class GuildWorld {
  public static final String NONE = "none";
  static final String TSUKIKAGE = "tsukikage";
  /** Occupations found only in Tsukikage, and those it does not have. */
  static final Set<String> TSUKIKAGE_ONLY = Set.of("侍", "浪人", "巫女");
  static final Set<String> NOT_IN_TSUKIKAGE = Set.of("騎士");

  private final JdbcTemplate jdbc;

  public record Village(String key, String name, String description) { }

  public record Faction(String key, String name, String description, String village) { }

  public record Faith(String key, String name, String description) { }

  public Map<String, Village> villages() {
    Map<String, Village> villages = new LinkedHashMap<>();
    jdbc.query("SELECT village_key, name, description FROM M_GUILD_VILLAGE ORDER BY sort_order",
        rs -> { villages.put(rs.getString(1), new Village(rs.getString(1), rs.getString(2), rs.getString(3))); });
    return villages;
  }

  public Map<String, Faction> factions() {
    Map<String, Faction> factions = new LinkedHashMap<>();
    jdbc.query("SELECT faction_key, name, description, village_key FROM M_GUILD_FACTION ORDER BY sort_order",
        rs -> { factions.put(rs.getString(1), new Faction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))); });
    return factions;
  }

  public Map<String, Faith> faiths() {
    Map<String, Faith> faiths = new LinkedHashMap<>();
    jdbc.query("SELECT faith_key, name, description FROM M_GUILD_FAITH ORDER BY sort_order",
        rs -> { faiths.put(rs.getString(1), new Faith(rs.getString(1), rs.getString(2), rs.getString(3))); });
    return faiths;
  }

  /** One change of where a resident lives, their faction or faith: keys and master-table names, oldest first. */
  public record Change(java.time.OffsetDateTime at, String kind, String fromKey, String toKey, String from, String to,
                       String changeKind, String reason, Long interactionId) {
    private static final Map<String, String> KINDS = Map.of("village", "暮らす村", "faction", "派閥", "faith", "信仰");
    static final Map<String, String> CHANGES = Map.of("initial", "初期値", "birth", "誕生",
        "move", "移住", "join", "加入", "leave", "脱退", "expel", "追放", "convert", "改宗");

    public java.time.LocalDate day() {
      return at.atZoneSameInstant(GuildService.TOKYO).toLocalDate();
    }

    public String text() {
      return KINDS.get(kind) + "：" + (from == null ? to : from + " → " + to) + "（" + CHANGES.get(changeKind) + "）";
    }
  }

  /** Every value is a master-table name; reasons are fixed text or an event's summary. */
  public List<Change> history(long id) {
    return jdbc.query("""
        SELECT h.changed_at, h.kind, h.from_value, h.to_value, COALESCE(fv.name, ff.name, fi.name),
          COALESCE(tv.name, tf.name, ti.name), h.change_kind, h.reason, h.interaction_id
        FROM T_GUILD_AFFILIATION_HISTORY h
        LEFT JOIN M_GUILD_VILLAGE fv ON h.kind = 'village' AND fv.village_key = h.from_value
        LEFT JOIN M_GUILD_FACTION ff ON h.kind = 'faction' AND ff.faction_key = h.from_value
        LEFT JOIN M_GUILD_FAITH fi ON h.kind = 'faith' AND fi.faith_key = h.from_value
        LEFT JOIN M_GUILD_VILLAGE tv ON h.kind = 'village' AND tv.village_key = h.to_value
        LEFT JOIN M_GUILD_FACTION tf ON h.kind = 'faction' AND tf.faction_key = h.to_value
        LEFT JOIN M_GUILD_FAITH ti ON h.kind = 'faith' AND ti.faith_key = h.to_value
        WHERE h.character_id = ? ORDER BY h.changed_at, h.id
        """, (rs, row) -> new Change(rs.getObject(1, java.time.OffsetDateTime.class), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), (Long) rs.getObject(9, Long.class)), id);
  }

  public record Count(String kind, String name, int count) { }

  /** How many living residents live in each village, belong to each faction and hold each faith. */
  public List<Count> counts() {
    List<Count> counts = new java.util.ArrayList<>();
    String alive = "SELECT COUNT(*) FROM M_GUILD_CHARACTER WHERE status <> 'DEAD' AND ";
    villages().values().forEach(v -> counts.add(new Count("村", v.name(), jdbc.queryForObject(alive + "current_village = ?", Integer.class, v.key()))));
    factions().values().forEach(f -> counts.add(new Count("派閥", f.name(), jdbc.queryForObject(alive + "faction = ?", Integer.class, f.key()))));
    faiths().values().forEach(f -> counts.add(new Count("信仰", f.name(), jdbc.queryForObject(alive + "faith = ?", Integer.class, f.key()))));
    return counts;
  }

  /** Whether someone with this occupation, living in village, may join a faction seated in factionVillage. */
  public static boolean canJoin(String occupation, String village, String factionVillage) {
    if (factionVillage == null || !factionVillage.equals(village)) return false;
    if (TSUKIKAGE_ONLY.contains(occupation) && !TSUKIKAGE.equals(factionVillage)) return false;
    return !(NOT_IN_TSUKIKAGE.contains(occupation) && TSUKIKAGE.equals(factionVillage));
  }

  /** The spirit faith is called the faith of the myriad gods in Tsukikage. */
  public static String faithName(String faithName, String faithKey, String village) {
    return "spirit".equals(faithKey) && TSUKIKAGE.equals(village) ? faithName + "（八百万信仰）" : faithName;
  }

  /** Whether a newcomer with these traits fits the world; null when it does, else why not. */
  public String misfit(String village, String faction, String occupation, GuildPeople.Lineage lineage) {
    if (TSUKIKAGE.equals(village) != (lineage == GuildPeople.Lineage.JAPANESE)) return "Village does not fit name lineage";
    if (TSUKIKAGE_ONLY.contains(occupation) && !TSUKIKAGE.equals(village)) return "Occupation not in village";
    if (NOT_IN_TSUKIKAGE.contains(occupation) && TSUKIKAGE.equals(village)) return "Occupation not in village";
    Faction seated = factions().get(faction);
    if (seated == null) return "Unknown faction";
    if (seated.village() != null && !seated.village().equals(village)) return "Faction not in village";
    return null;
  }

  /** The world as the AI is told it, only the parts named in keys (villages, factions and faiths together). */
  public String describe(Set<String> keys) {
    List<String> lines = new java.util.ArrayList<>();
    villages().values().stream().filter(v -> keys.contains(v.key())).forEach(v -> lines.add("村 " + v.name() + "：" + v.description()));
    factions().values().stream().filter(f -> keys.contains(f.key()) && !f.key().equals(NONE))
        .forEach(f -> lines.add("派閥 " + f.name() + "：" + f.description()));
    faiths().values().stream().filter(f -> keys.contains(f.key()) && !f.key().equals(NONE))
        .forEach(f -> lines.add("信仰 " + f.name() + "：" + f.description()));
    return String.join("\n", lines);
  }
}
