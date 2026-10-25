package com.yuki.rubinco.guild;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What code decides about a newcomer before the AI writes them: gender, occupation, name lineage and
 * the pixel parts they wear. The AI only names and describes the person it is given.
 */
@Component
@RequiredArgsConstructor
public class GuildPeople {
  private final GuildProperties properties;

  /** Occupation → outfit. Several occupations may share an outfit. */
  static final Map<String, String> OUTFITS = Map.ofEntries(
      Map.entry("農家", "farmer"), Map.entry("夜警", "watch"), Map.entry("騎士", "knight"),
      Map.entry("職人", "crafter"), Map.entry("鉱夫", "crafter"), Map.entry("商人", "merchant"),
      Map.entry("聖職者", "cleric"), Map.entry("冒険者", "adventurer"), Map.entry("猟師", "hunter"),
      Map.entry("漁師", "hunter"), Map.entry("料理人", "cook"));
  /** Outfits that dress the legs themselves; the rest wear trousers or a skirt by gender. */
  private static final List<String> FULL_OUTFITS = List.of("farmer", "knight", "knight_f", "cleric");
  /** Outfits with headwear drawn over the hair. */
  private static final List<String> HATS = List.of("farmer", "watch", "knight", "knight_f", "crafter", "merchant", "hunter", "cook");

  /** Which occupations a memory's category tends to bring, with weights. */
  private static final Map<String, Map<String, Integer>> BY_CATEGORY = Map.ofEntries(
      Map.entry("bloodmoon", weights("夜警", 4, "騎士", 4, "冒険者", 1, "聖職者", 1)),
      Map.entry("combat", weights("騎士", 3, "冒険者", 3, "夜警", 2, "猟師", 2)),
      Map.entry("survival", weights("聖職者", 3, "冒険者", 2, "夜警", 1, "料理人", 1)),
      Map.entry("exploration", weights("冒険者", 4, "商人", 1, "猟師", 1)),
      Map.entry("gathering", weights("職人", 2, "鉱夫", 2, "農家", 2, "冒険者", 1)),
      Map.entry("travel", weights("商人", 4, "冒険者", 2)),
      Map.entry("fishing", weights("漁師", 4, "料理人", 2, "農家", 1)),
      Map.entry("building", weights("職人", 4, "鉱夫", 1, "農家", 1)),
      Map.entry("mining", weights("鉱夫", 4, "職人", 2, "冒険者", 1)),
      Map.entry("boss", weights("騎士", 3, "冒険者", 3, "聖職者", 1)),
      Map.entry("hunting", weights("猟師", 4, "冒険者", 2, "騎士", 1)),
      Map.entry("cooking", weights("料理人", 4, "農家", 2, "商人", 1)),
      Map.entry("farming", weights("農家", 5, "料理人", 2)));
  private static final Map<String, Integer> ANY = weights("冒険者", 2, "農家", 1, "職人", 1, "商人", 1, "夜警", 1, "騎士", 1);

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

  public record Plan(String gender, String occupation, Lineage lineage, Look look) { }

  public Plan plan(String category) {
    var random = ThreadLocalRandom.current();
    String gender = random.nextDouble() < properties.femaleRatio() ? "女性" : "男性";
    String occupation = draw(BY_CATEGORY.getOrDefault(category, ANY));
    String outfit = OUTFITS.get(occupation);
    if (outfit.equals("knight") && gender.equals("女性")) outfit = "knight_f";
    Look look = Look.of(outfit, any(gender.equals("女性") ? FEMALE_HAIR : MALE_HAIR), any(HAIR_COLORS), any(SKINS),
        any(CLOTHS), any(SECOND), gender);
    return new Plan(gender, occupation, Lineage.valueOf(draw(lineageWeights())), look);
  }

  private Map<String, Integer> lineageWeights() {
    Map<String, Integer> weights = new LinkedHashMap<>();
    properties.lineageWeights().forEach((key, weight) -> {
      Lineage lineage = Lineage.of(key);
      if (lineage != null && weight > 0) weights.put(lineage.name(), weight);
    });
    return weights.isEmpty() ? Map.of(Lineage.EUROPEAN.name(), 1) : weights;
  }

  private static String draw(Map<String, Integer> weights) {
    int total = weights.values().stream().mapToInt(Integer::intValue).sum();
    int roll = ThreadLocalRandom.current().nextInt(total);
    for (var entry : weights.entrySet()) {
      roll -= entry.getValue();
      if (roll < 0) return entry.getKey();
    }
    throw new IllegalStateException("empty weights");
  }

  private static String any(List<String> values) {
    return values.get(ThreadLocalRandom.current().nextInt(values.size()));
  }

  private static Map<String, Integer> weights(Object... pairs) {
    Map<String, Integer> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Integer) pairs[i + 1]);
    return map;
  }
}
