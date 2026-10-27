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
 * The choices a newcomer may be given and the SD illustration parts they wear. The AI reads the memory and picks
 * gender, occupation and name lineage from what this offers; whatever the last residents already have
 * too much of is left out, so the town does not fill with one kind of person.
 */
@Component
@RequiredArgsConstructor
public class GuildPeople {
  private final GuildProperties properties;
  private final JdbcTemplate jdbc;
  private final GuildWorld world;

  /** Occupation → outfit (men's cut). Several occupations may share an outfit; women wear the "_f" cut where one exists. */
  static final Map<String, String> OUTFITS = Map.ofEntries(
      Map.entry("農家", "farmer"), Map.entry("夜警", "watch"), Map.entry("騎士", "knight"),
      Map.entry("職人", "crafter"), Map.entry("鉱夫", "crafter"), Map.entry("商人", "merchant"),
      Map.entry("聖職者", "cleric"), Map.entry("冒険者", "adventurer"), Map.entry("猟師", "hunter"),
      Map.entry("漁師", "hunter"), Map.entry("料理人", "cook"), Map.entry("鍛冶屋", "crafter"), Map.entry("大工", "crafter"),
      Map.entry("薬師", "cleric"), Map.entry("運び屋", "merchant"), Map.entry("護衛", "watch"),
      Map.entry("侍", "samurai"), Map.entry("浪人", "ronin"), Map.entry("巫女", "miko_m"));
  /** Outfits with a women's cut, and the cut's name. A woman shrine attendant wears the shrine maiden's dress. */
  static final Map<String, String> WOMEN = Map.ofEntries(Map.entry("farmer", "farmer_f"), Map.entry("watch", "watch_f"),
      Map.entry("knight", "knight_f"), Map.entry("crafter", "crafter_f"), Map.entry("merchant", "merchant_f"),
      Map.entry("cleric", "cleric_f"), Map.entry("adventurer", "adventurer_f"), Map.entry("hunter", "hunter_f"),
      Map.entry("cook", "cook_f"), Map.entry("samurai", "samurai_f"), Map.entry("ronin", "ronin_f"), Map.entry("miko_m", "miko"));
  static final List<String> GENDERS = List.of("女性", "男性");
  /** Outfits that dress the legs themselves; the rest wear trousers or a skirt by gender. */
  private static final List<String> FULL_OUTFITS = List.of("farmer", "knight", "knight_f", "cleric", "cleric_f", "farmer_f", "merchant_f",
      "cook_f", "samurai", "samurai_f", "ronin", "ronin_f", "miko", "miko_m");
  /** Outfits with headwear drawn over the hair. */
  private static final List<String> HATS = List.of("farmer", "watch", "knight", "knight_f", "crafter", "crafter_f", "merchant", "hunter",
      "cook", "cook_f", "cleric_f", "ronin", "miko_m");

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

  /** Hair styles and accessories are split by gender; glasses suit anyone. "none" leaves the face bare. */
  static final List<String> MALE_HAIR = List.of("short", "cropped", "spiky", "swept", "shaggy", "topknot", "tail",
      "sidepart", "curly", "undercut", "slicked", "messy", "centerpart", "quiff", "wolf", "buzz",
      "layered", "windswept", "lowtail", "highknot", "mohawk");
  static final List<String> FEMALE_HAIR = List.of("long", "bob", "tied", "braid", "twintail", "bun", "wavy", "hime",
      "pixie", "lob", "sidebraid", "doublebraid", "lowbun", "doublebun", "highpony", "sidepony",
      "ringlets", "feathered", "straight", "halfup", "crown", "shortwave", "ribbonbraid", "sidelong");
  static final List<String> EYE_STYLES = List.of("round", "almond", "droop", "sharp", "sleepy", "wide", "narrow", "sparkle", "smile");
  static final List<String> BROW_STYLES = List.of("natural", "straight", "arched");
  static final List<String> OUTFIT_VARIANTS = List.of("classic", "travel", "formal");
  static final List<String> HEAD_SHAPES = List.of("soft", "round", "angular");
  static final List<String> POSES = List.of("rest", "wave", "crossed", "mug", "think");
  static final List<String> MALE_ACCESSORIES = List.of("none", "none", "none", "beard", "mustache", "stubble", "eyepatch", "scar", "glasses");
  static final List<String> FEMALE_ACCESSORIES = List.of("none", "none", "none", "ribbon", "flower", "earrings", "hairband", "glasses");
  private static final List<String> HAIR_COLORS = List.of("#2b2224", "#4a2f25", "#6e4630", "#a0683a", "#d6a85a", "#e8d6b0", "#8c8c96", "#a0413c",
      "#1d2238", "#c9c9d2", "#7a2e2e", "#d98c5f", "#f0e2b6", "#3b4f6b", "#5a3a6a");
  private static final List<String> SKINS = List.of("#f3d2b3", "#e6b48e", "#d29a72", "#a8704c", "#7a4e36", "#fbe2cc", "#c48a62", "#5c3a28");
  private static final List<String> CLOTHS = List.of("#7b5a8f", "#5f7d4a", "#4f6d8f", "#a0544a", "#8a6a3a", "#3f5f63", "#6b4a6e", "#7a7468",
      "#2f5d50", "#b07a2f", "#6b2f3f", "#2e3a5f", "#8a8f3a", "#c06a8a", "#4a7a8c", "#9a4a2a");
  private static final List<String> SECOND = List.of("#5a4a6a", "#4a5f3a", "#3a4f6a", "#7a3a3a", "#6a5a3a", "#3a4a4f",
      "#2a2a3a", "#8a4a5a", "#3a6a5a", "#b0905a", "#5a2a4a");
  private static final List<String> EYES = List.of("#2b2032", "#2b2032", "#3a5a8a", "#3f6b3a", "#6b4a2a", "#6a3a7a", "#8a6a2a");
  private static final java.util.Set<String> ALL_OUTFITS = java.util.stream.Stream.concat(OUTFITS.values().stream(), WOMEN.values().stream())
      .collect(Collectors.toUnmodifiableSet());

  /**
   * The illustration parts of one person. Generated colours come from the palettes above. face follows gender (null when
   * unknown); accessory and eye are null for residents born before V60.
   */
  public record Look(String outfit, String hair, String hairColor, String skin, String cloth, String cloth2, String legs,
                     String face, String accessory, String eye, String eyeStyle, String browStyle, String outfitVariant) {
    /** Symbol ids in /img/guild-people.svg, drawn bottom to top. */
    public List<String> layers() {
      return partLayers("soft", "rest");
    }

    private List<String> partLayers(String contour, String pose) {
      boolean posed = !pose.equals("rest");
      String cut = posed ? "-posed" : "";
      List<String> layers = new ArrayList<>(List.of(posed ? "body-posed" : "body-core", "head-" + contour));
      layers.add("eyes-" + eyeStyle);
      if (face != null) layers.add("face-" + face);
      layers.add("brows-" + browStyle);
      if (legs != null) layers.add("legs-" + legs);
      layers.add("outfit-" + outfit + cut);
      if (!outfitVariant.equals("classic")) layers.add("dress-" + outfit + "-" + outfitVariant + cut);
      layers.add("hair-" + hair);
      if (accessory != null && !accessory.equals("none")) layers.add("acc-" + accessory);
      if (HATS.contains(outfit)) layers.add("hat-" + outfit);
      if (posed) layers.add("pose-" + outfit + "-" + pose);
      return layers;
    }

    /** Permanent character cues, not the emotion of the current dialogue. No new stored/random choices. */
    public List<String> layers(Integer age, String personality) {
      return layers(age, personality, "soft", "rest");
    }

    public List<String> layers(Integer age, String personality, String contour, String pose) {
      if (contour == null || !HEAD_SHAPES.contains(contour)) contour = "soft";
      if (pose == null || !POSES.contains(pose)) pose = "rest";
      List<String> layers = partLayers(contour, pose);
      int faceEnd = face == null ? 4 : 5;
      if (age != null) {
        if (age >= 60) layers.add(faceEnd, "age-elder");
        else if (age >= 40) layers.add(faceEnd, "age-mature");
        else if (age < 20) layers.add(faceEnd, "age-young");
      }
      String expression = expression(personality);
      if (expression != null) layers.add(faceEnd, "expression-" + expression);
      return layers;
    }

    /** Independent bust artwork, sharing saved identity choices and facial cues with the SD figure. */
    public List<String> portraitLayers(Integer age, String personality, String contour, String pose) {
      List<String> layers = layers(age, personality, contour, "rest");
      layers.set(0, "body");
      layers.removeIf(layer -> layer.startsWith("legs-"));
      layers.add(0, "hair-back-" + hair);
      layers.add(layers.indexOf("hair-" + hair), "pose-" + outfit + "-" + (pose != null && POSES.contains(pose) ? pose : "rest"));
      return layers;
    }

    public String style() {
      return "--skin:" + skin + ";--hair:" + hairColor + ";--cloth:" + cloth + ";--cloth2:" + cloth2 + (eye == null ? "" : ";--eye:" + eye);
    }

    static Look of(String outfit, String hair, String hairColor, String skin, String cloth, String cloth2, String gender) {
      return of(outfit, hair, hairColor, skin, cloth, cloth2, gender, null, null);
    }

    /** Null unless every part is a known one; old or edited rows fall back to the plain resident. */
    static Look of(String outfit, String hair, String hairColor, String skin, String cloth, String cloth2, String gender,
                   String accessory, String eye) {
      return of(outfit, hair, hairColor, skin, cloth, cloth2, gender, accessory, eye, null, null, null);
    }

    static Look of(String outfit, String hair, String hairColor, String skin, String cloth, String cloth2, String gender,
                   String accessory, String eye, String eyeStyle, String browStyle, String outfitVariant) {
      if (outfit == null || !ALL_OUTFITS.contains(outfit)) return null;
      if (!MALE_HAIR.contains(hair) && !FEMALE_HAIR.contains(hair)) return null;
      if (accessory != null && !MALE_ACCESSORIES.contains(accessory) && !FEMALE_ACCESSORIES.contains(accessory)) return null;
      for (String color : new String[] {hairColor, skin, cloth, cloth2}) {
        if (color == null || !color.matches("#[0-9a-f]{6}")) return null;
      }
      if (eye != null && !eye.matches("#[0-9a-f]{6}")) return null;
      if (eyeStyle == null) eyeStyle = "round";
      if (browStyle == null) browStyle = "natural";
      if (outfitVariant == null) outfitVariant = "classic";
      if (!EYE_STYLES.contains(eyeStyle) || !BROW_STYLES.contains(browStyle) || !OUTFIT_VARIANTS.contains(outfitVariant)) return null;
      String legs = FULL_OUTFITS.contains(outfit) ? null : "女性".equals(gender) ? "skirt" : "pants";
      String face = "女性".equals(gender) ? "f" : "男性".equals(gender) ? "m" : null;
      return new Look(outfit, hair, hairColor, skin, cloth, cloth2, legs, face, accessory, eye, eyeStyle, browStyle, outfitVariant);
    }
  }

  /** Conservative text hints; unrecognised profiles keep the neutral face. */
  private static String expression(String personality) {
    if (personality == null) return null;
    if (containsAny(personality, "陽気", "快活", "明朗", "明るい", "お調子者", "人懐っこ")) return "bright";
    if (containsAny(personality, "勇敢", "豪快", "勝気", "勝ち気", "頑固", "強気")) return "resolute";
    if (containsAny(personality, "慎重", "寡黙", "内気", "心配性", "思慮深")) return "thoughtful";
    if (containsAny(personality, "穏やか", "温厚", "優しい", "おっとり", "世話好き")) return "gentle";
    return null;
  }

  private static boolean containsAny(String text, String... hints) {
    for (String hint : hints) if (text.contains(hint)) return true;
    return false;
  }

  static String pose(String text, String fallback) {
    if (text == null) return fallback;
    if (containsAny(text, "飲", "カップ", "グラス", "一杯", "乾杯")) return "mug";
    if (containsAny(text, "手を振", "挨拶", "ようこそ", "こんにちは", "陽気", "快活", "人懐っこ")) return "wave";
    if (containsAny(text, "腕を組", "腕組", "頑固", "強気", "勝気", "勇敢")) return "crossed";
    if (containsAny(text, "考え", "悩", "慎重", "思慮深", "寡黙")) return "think";
    return fallback;
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

  /** The illustration parts for a gender and occupation; hair and colours are drawn from the palettes. */
  public Look look(String gender, String occupation) {
    return redraw(gender, occupation, null, false);
  }

  /** Redraw only stored visual choices; a full redraw retains an established skin tone. */
  public Look redraw(String gender, String occupation, Look previous, boolean eyesOnly) {
    if (eyesOnly && previous != null) {
      return Look.of(previous.outfit(), previous.hair(), previous.hairColor(), previous.skin(), previous.cloth(), previous.cloth2(),
          gender, previous.accessory(), previous.eye(), other(EYE_STYLES, previous.eyeStyle()),
          other(BROW_STYLES, previous.browStyle()), previous.outfitVariant());
    }
    boolean woman = "女性".equals(gender);
    String outfit = occupation == null ? "adventurer" : OUTFITS.getOrDefault(occupation, "adventurer");
    if (woman) outfit = WOMEN.getOrDefault(outfit, outfit);
    // A topknot goes with Tsukikage's swords; elsewhere it is rare.
    List<String> hair = woman ? FEMALE_HAIR : outfit.equals("samurai") || outfit.equals("ronin") ? List.of("topknot", "highknot", "tail", "lowtail", "shaggy", "layered", "slicked", "swept")
        : MALE_HAIR.stream().filter(style -> !List.of("topknot", "highknot").contains(style)).toList();
    return Look.of(outfit, other(hair, previous == null ? null : previous.hair()), any(HAIR_COLORS),
        previous == null ? any(SKINS) : previous.skin(), any(CLOTHS), any(SECOND), gender,
        any(woman ? FEMALE_ACCESSORIES : MALE_ACCESSORIES), any(EYES),
        other(EYE_STYLES, previous == null ? null : previous.eyeStyle()), any(BROW_STYLES), any(OUTFIT_VARIANTS));
  }

  private static String other(List<String> values, String previous) {
    return any(values.stream().filter(value -> !value.equals(previous)).toList());
  }

  private static String any(List<String> values) {
    return values.get(ThreadLocalRandom.current().nextInt(values.size()));
  }
}
