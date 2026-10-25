package com.yuki.rubinco.guild;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The event cards in guild/event-cards.yml, checked once at startup. */
@Component
public class GuildCards {
  static final Set<String> TIERS = Set.of("daily", "progress", "thread", "major");
  static final Set<String> AXES = Set.of("trust", "affinity", "wariness", "debt");
  /** What an outcome may change about {a}: join {b}'s faction, leave or be expelled from their own, move to {b}'s village, take {b}'s faith. */
  static final Set<String> CHANGES = Set.of("join", "leave", "expel", "move", "convert");
  private static final Set<String> SAME_OR_DIFFERENT = Set.of("same", "different");

  /** An axis change as an inclusive range per axis, e.g. affinity: [1, 3]. */
  public record Delta(Map<String, int[]> ranges) {
    static final Delta NONE = new Delta(Map.of());
  }

  public record Opening(String kind, int days, String summary) { }

  public record Outcome(String id, int chance, String summary, String template, Delta both, Delta aToB, Delta bToA,
                        Opening open, String resolve, String label, String change) { }

  public record When(List<String> labels, List<String> notLabels, List<String> phases, Integer affinityMin,
                     Integer affinityMax, Integer trustMin, Integer warinessMin, String thread, Integer cooldownDays,
                     boolean adults, Integer ageGapMin, boolean memorial,
                     String faction, String faith, String village, String aFaction, String bFaction, String aFaith, String bFaith,
                     List<String> aChanged, boolean former) {
    /** Whether the card is about villages, factions or faiths at all. */
    boolean affiliation() {
      return faction != null || faith != null || village != null || aFaction != null || bFaction != null || aFaith != null
          || bFaith != null || !aChanged.isEmpty() || former;
    }
  }

  public record Card(String id, String kind, String tier, int weight, When when, List<Outcome> outcomes) {
    List<String> changes() {
      return outcomes.stream().map(Outcome::change).filter(java.util.Objects::nonNull).distinct().toList();
    }
  }

  private final String version;
  private final List<Card> cards;

  public GuildCards() {
    this(new ClassPathResource("guild/event-cards.yml"));
  }

  GuildCards(ClassPathResource resource) {
    try (InputStream in = resource.getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      version = String.valueOf(root.get("version"));
      List<Card> loaded = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      for (Object item : list(root.get("cards"))) {
        Card card = card(map(item));
        if (!ids.add(card.id())) throw new IllegalStateException("Duplicate guild card " + card.id());
        loaded.add(card);
      }
      cards = List.copyOf(loaded);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  public String version() {
    return version;
  }

  public List<Card> cards() {
    return cards;
  }

  private static Card card(Map<String, Object> raw) {
    String id = text(raw, "id");
    String tier = text(raw, "tier");
    if (!TIERS.contains(tier)) throw new IllegalStateException("Unknown tier in guild card " + id);
    int weight = ((Number) raw.get("weight")).intValue();
    Map<String, Object> when = raw.containsKey("when") ? map(raw.get("when")) : Map.of();
    List<String> phases = strings(raw.get("phases"));
    When condition = new When(strings(when.get("labels")), strings(when.get("not_labels")), phases,
        integer(when.get("affinity_min")), integer(when.get("affinity_max")), integer(when.get("trust_min")),
        integer(when.get("wariness_min")), (String) when.get("thread"), integer(when.get("cooldown_days")),
        Boolean.TRUE.equals(when.get("adults")), integer(when.get("age_gap_min")), Boolean.TRUE.equals(when.get("memorial")),
        choice(when, "faction", SAME_OR_DIFFERENT, id), choice(when, "faith", SAME_OR_DIFFERENT, id),
        choice(when, "village", SAME_OR_DIFFERENT, id), choice(when, "a_faction", Set.of("none", "member"), id),
        choice(when, "b_faction", Set.of("none", "member"), id), choice(when, "a_faith", Set.of("none", "believer"), id),
        choice(when, "b_faith", Set.of("none", "believer"), id), strings(when.get("a_changed")), Boolean.TRUE.equals(when.get("former")));
    if (!CHANGES.containsAll(condition.aChanged())) throw new IllegalStateException("Unknown a_changed in guild card " + id);
    List<Outcome> outcomes = new ArrayList<>();
    for (Object item : list(raw.get("outcomes"))) {
      Map<String, Object> o = map(item);
      Map<String, Object> open = o.containsKey("open") ? map(o.get("open")) : null;
      outcomes.add(new Outcome(text(o, "id"), ((Number) o.get("chance")).intValue(), text(o, "summary"), text(o, "template"),
          delta(o.get("both")), delta(o.get("a_to_b")), delta(o.get("b_to_a")),
          open == null ? null : new Opening(text(open, "kind"), ((Number) open.get("days")).intValue(), text(open, "summary")),
          (String) o.get("resolve"), (String) o.get("label"), (String) o.get("change")));
      if (o.get("change") != null && !CHANGES.contains(o.get("change"))) throw new IllegalStateException("Unknown change in guild card " + id);
    }
    if (weight <= 0 || outcomes.isEmpty()) throw new IllegalStateException("Guild card needs weight and outcomes: " + id);
    return new Card(id, text(raw, "kind"), tier, weight, condition, List.copyOf(outcomes));
  }

  private static String choice(Map<String, Object> when, String key, Set<String> allowed, String id) {
    Object value = when.get(key);
    if (value == null) return null;
    if (!allowed.contains(String.valueOf(value))) throw new IllegalStateException("Unknown " + key + " in guild card " + id);
    return String.valueOf(value);
  }

  private static Delta delta(Object raw) {
    if (raw == null) return Delta.NONE;
    Map<String, int[]> ranges = new java.util.LinkedHashMap<>();
    map(raw).forEach((axis, value) -> {
      if (!AXES.contains(axis)) throw new IllegalStateException("Unknown guild axis " + axis);
      List<Object> pair = list(value);
      int min = ((Number) pair.get(0)).intValue();
      int max = ((Number) pair.get(1)).intValue();
      if (min > max || Math.abs(min) > 20 || Math.abs(max) > 20) throw new IllegalStateException("Guild delta out of range: " + axis);
      ranges.put(axis, new int[] {min, max});
    });
    return new Delta(Map.copyOf(ranges));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return value == null ? List.of() : (List<Object>) value;
  }

  private static List<String> strings(Object value) {
    return list(value).stream().map(String::valueOf).toList();
  }

  private static Integer integer(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private static String text(Map<String, Object> raw, String key) {
    Object value = raw.get(key);
    if (value == null || String.valueOf(value).isBlank()) throw new IllegalStateException("Guild card is missing " + key);
    return String.valueOf(value);
  }
}
