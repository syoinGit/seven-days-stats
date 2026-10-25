package com.yuki.rubinco.guild;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The choices a newcomer may be given and the pixel parts they wear. The AI reads the memory and picks
 * gender, occupation and name lineage from what this offers; whatever the last residents already have
 * too much of is left out, so the town does not fill with one kind of person.
 */
@Component
@RequiredArgsConstructor
public class GuildPeople {
  private final GuildProperties properties;
  private final JdbcTemplate jdbc;
  private final GuildWorld world;

  /** Occupation → outfit. Several occupations may share an outfit. */
  static final Map<String, String> OUTFITS = Map.ofEntries(
      Map.entry("農家", "farmer"), Map.entry("夜警", "watch"), Map.entry("騎士", "knight"),
      Map.entry("職人", "crafter"), Map.entry("鉱夫", "crafter"), Map.entry("商人", "merchant"),
      Map.entry("聖職者", "cleric"), Map.entry("冒険者", "adventurer"), Map.entry("猟師", "hunter"),
      Map.entry("漁師", "hunter"), Map.entry("料理人", "cook"), Map.entry("鍛冶屋", "crafter"), Map.entry("大工", "crafter"),
      Map.entry("薬師", "cleric"), Map.entry("運び屋", "merchant"), Map.entry("護衛", "watch"),
      Map.entry("侍", "watch"), Map.entry("浪人", "adventurer"), Map.entry("巫女", "cleric"));
  static final List<String> GENDERS = List.of("女性", "男性");
  /** Outfits that dress the legs themselves; the rest wear trousers or a skirt by gender. */
  private static final List<String> FULL_OUTFITS = List.of("farmer", "knight", "knight_f", "cleric");
  /** Outfits with headwear drawn over the hair. */
  private static final List<String> HATS = List.of("farmer", "watch", "knight", "knight_f", "crafter", "merchant", "hunter", "cook");

  /** Name lineages: how the AI is asked to name the person, and how the name is checked. */
  public enum Lineage {
    JAPANESE("日本系", "和名。漢字またはひらがなの名だけ（姓なし）、2〜6文字", "[\\p{IsHan}\\p{IsHiragana}々]{2,6}"),
    EUROPEAN("ヨーロッパ系", "中世ヨーロッパ風の名。カタカナの名だけ（姓なし）、2〜8文字", "[\\p{IsKatakana}ー]{2,8}"),
    NORTH("北方系", "雪深い北方の架空の地方風の名。カタカナ、2〜8文字", "[\\p{IsKatakana}ー]{2,8}"),
    DESERT("砂漠系", "乾いた砂漠の架空の地方風の名。カタカナ、2〜8文字", "[\\p{IsKatakana}ー]{2,8}"),
    STEPPE("草原系", "風の吹く草原の架空の地方風の名。カタカナ、2〜8文字", "[\\p{IsKatakana}ー]{2,8}");

    private final String label;
    private final String rule;
    private final String pattern;

    Lineage(String label, String rule, String pattern) {
      this.label = label;
      this.rule = rule;
      this.pattern = pattern;
    }

    public String label() {
      return label;
    }

    public String rule() {
      return rule;
    }

    public boolean fits(String name) {
      return name.matches(pattern);
    }

    public static Lineage of(String key) {
      for (Lineage lineage : values()) {
        if (lineage.name().equalsIgnoreCase(key)) return lineage;
      }
      return null;
    }
  }

  private static final List<String> MALE_HAIR = List.of("short", "short", "cropped", "cropped", "tied", "long");
  private static final List<String> FEMALE_HAIR = List.of("long", "long", "bob", "bob", "tied", "tied", "short");
  private static final List<String> HAIR_COLORS = List.of("#2b2224", "#4a2f25", "#6e4630", "#a0683a", "#d6a85a", "#e8d6b0", "#8c8c96", "#a0413c");
  private static final List<String> SKINS = List.of("#f3d2b3", "#e6b48e", "#d29a72", "#a8704c", "#7a4e36");
  private static final List<String> CLOTHS = List.of("#7b5a8f", "#5f7d4a", "#4f6d8f", "#a0544a", "#8a6a3a", "#3f5f63", "#6b4a6e", "#7a7468");
  private static final List<String> SECOND = List.of("#5a4a6a", "#4a5f3a", "#3a4f6a", "#7a3a3a", "#6a5a3a", "#3a4a4f");

  /** The pixel parts of one person. Colours are always from the palettes above. */
  public record Look(String outfit, String hair, String hairColor, String skin, String cloth, String cloth2, String legs) {
    /** Symbol ids in /img/guild-people.svg, drawn bottom to top. */
    public List<String> layers() {
      List<String> layers = new ArrayList<>(List.of("body"));
      if (legs != null) layers.add("legs-" + legs);
      layers.add("outfit-" + outfit);
      layers.add("hair-" + hair);
      if (HATS.contains(outfit)) layers.add("hat-" + outfit);
      return layers;
    }

    public String style() {
      return "--skin:" + skin + ";--hair:" + hairColor + ";--cloth:" + cloth + ";--cloth2:" + cloth2;
    }

    /** Null unless every part is a known one; old or edited rows fall back to the plain resident. */
    static Look of(String outfit, String hair, String hairColor, String skin, String cloth, String cloth2, String gender) {
      if (outfit == null || !OUTFITS.containsValue(outfit) && !outfit.equals("knight_f")) return null;
      if (!List.of("short", "cropped", "tied", "long", "bob").contains(hair)) return null;
      for (String color : new String[] {hairColor, skin, cloth, cloth2}) {
        if (color == null || !color.matches("#[0-9a-f]{6}")) return null;
      }
      String legs = FULL_OUTFITS.contains(outfit) ? null : "女性".equals(gender) ? "skirt" : "pants";
      return new Look(outfit, hair, hairColor, skin, cloth, cloth2, legs);
    }
  }

  /** What the AI may choose from, and the last residents (newest first) so it can tell what the town already has. */
  public record Options(List<String> genders, List<String> occupations, List<Lineage> lineages, List<String> villages,
                        List<String> factions, List<String> faiths, List<String> recent) { }

  public Options options() {
    record Resident(String name, String gender, String occupation, Lineage lineage, String village, String faction) { }
    List<Resident> recent = jdbc.query("SELECT name, gender, occupation, name_lineage, home_village, faction FROM M_GUILD_CHARACTER "
        + "ORDER BY id DESC LIMIT ?", (rs, row) -> new Resident(rs.getString(1), rs.getString(2), rs.getString(3),
        Lineage.of(rs.getString(4)), rs.getString(5), rs.getString(6)), properties.categoryWindow());
    var villages = world.villages();
    var factions = world.factions();
    // "No faction" is always open; a faction already holding occupation-cap of the recent residents is not.
    List<String> openFactions = allowed(factions.keySet().stream().filter(key -> !key.equals(GuildWorld.NONE)).toList(),
        recent, Resident::faction, properties.occupationCap());
    List<Lineage> lineages = new ArrayList<>(allowed(List.of(Lineage.values()), recent, Resident::lineage, properties.lineageCap()));
    List<String> homes = new ArrayList<>(allowed(List.copyOf(villages.keySet()), recent, Resident::village, properties.lineageCap()));
    // Tsukikage and Japanese names go together (GuildWorld.misfit): offer both or neither.
    if (!lineages.contains(Lineage.JAPANESE) || !homes.contains(GuildWorld.TSUKIKAGE)) {
      if (lineages.size() > 1 && homes.size() > 1) {
        lineages.remove(Lineage.JAPANESE);
        homes.remove(GuildWorld.TSUKIKAGE);
      }
    }
    // Samurai, ronin and miko live only in Tsukikage: without it they would never fit.
    List<String> occupations = allowed(OUTFITS.keySet().stream().sorted().toList(), recent, Resident::occupation, properties.occupationCap())
        .stream().filter(job -> homes.contains(GuildWorld.TSUKIKAGE) || !GuildWorld.TSUKIKAGE_ONLY.contains(job)).toList();
    return new Options(allowed(GENDERS, recent, Resident::gender, properties.genderCap()), occupations,
        List.copyOf(lineages), List.copyOf(homes),
        java.util.stream.Stream.concat(openFactions.stream(), java.util.stream.Stream.of(GuildWorld.NONE)).toList(),
        List.copyOf(world.faiths().keySet()),
        recent.stream().limit(5).map(r -> r.name() + "（" + (r.gender() == null ? "不明" : r.gender()) + "・"
            + (r.occupation() == null ? "不明" : r.occupation()) + "・" + (r.lineage() == null ? "不明" : r.lineage().label())
            + "・" + (r.village() == null || !villages.containsKey(r.village()) ? "不明" : villages.get(r.village()).name())
            + "・" + (r.faction() == null || !factions.containsKey(r.faction()) ? "無所属" : factions.get(r.faction()).name()) + "）").toList());
  }

  /** Values already held by cap of the recent residents are left out, unless that would leave nothing. */
  private static <T, R> List<T> allowed(Collection<T> values, List<R> recent, Function<R, T> of, int cap) {
    Map<T, Long> counts = recent.stream().map(of).filter(java.util.Objects::nonNull)
        .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
    List<T> left = values.stream().filter(value -> counts.getOrDefault(value, 0L) < cap).toList();
    return left.isEmpty() ? List.copyOf(values) : left;
  }

  /** The pixel parts for a gender and occupation; hair and colours are drawn from the palettes. */
  public Look look(String gender, String occupation) {
    String outfit = OUTFITS.get(occupation);
    if (outfit.equals("knight") && gender.equals("女性")) outfit = "knight_f";
    return Look.of(outfit, any(gender.equals("女性") ? FEMALE_HAIR : MALE_HAIR), any(HAIR_COLORS), any(SKINS),
        any(CLOTHS), any(SECOND), gender);
  }

  private static String any(List<String> values) {
    return values.get(ThreadLocalRandom.current().nextInt(values.size()));
  }
}
