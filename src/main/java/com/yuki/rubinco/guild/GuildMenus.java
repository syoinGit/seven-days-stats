package com.yuki.rubinco.guild;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What the traveller can order at each place (guild/menus.yml): the visitor is a stranger who wandered in with a few
 * silver coins, orders within them, and whoever sits beside them reacts before they talk. Each place has its own menu,
 * drawn in its own style. A resident's favorite at a place is fixed by their id, so a regular can learn it. The menus
 * are checked at startup; nothing the visitor orders is sent back or kept.
 */
@Component
public class GuildMenus {
  static final Set<String> STYLES = Set.of("board", "parchment", "bark", "tags");
  static final int MAX_PRICE = 9;

  public record Item(String key, String name, int price, String about, String hint, boolean pour) { }

  public record Section(String name, List<Item> items) { }

  public record Pairing(String a, String b, String line) { }

  public record Menu(String place, String style, String enter, String board, String note, String payment,
                     List<Section> sections, List<Pairing> pairings) {
    public List<Item> items() {
      return sections.stream().flatMap(section -> section.items().stream()).toList();
    }

    public Item item(String key) {
      return items().stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
    }
  }

  private final String version;
  private final Map<String, Menu> menus;

  public GuildMenus() {
    try (InputStream in = new ClassPathResource("guild/menus.yml").getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      version = text(root, "version");
      Map<String, Menu> read = new LinkedHashMap<>();
      map(root.get("places"), "places").forEach((place, value) -> read.put(place, menu(place, map(value, place))));
      for (var place : GuildPlaces.ALL) {
        if (!read.containsKey(place.key())) throw new IllegalStateException("menus.yml: no menu for " + place.key());
      }
      menus = Map.copyOf(read);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String version() {
    return version;
  }

  /** The menu of this place (every place has one). */
  public Menu of(String place) {
    var menu = menus.get(place);
    if (menu == null) throw new IllegalArgumentException("no menu for " + place);
    return menu;
  }

  /** What this resident likes best at this place: one of its paid items, fixed by their id. */
  public Item favorite(long characterId, String place) {
    var paid = of(place).items().stream().filter(item -> item.price() > 0).toList();
    long seed = Math.floorMod(characterId * 2654435761L + place.hashCode() * 40503L, 1_000_003L);
    return paid.get((int) (seed % paid.size()));
  }

  private static Menu menu(String place, Map<String, Object> raw) {
    String style = text(raw, "style");
    if (!STYLES.contains(style)) throw new IllegalStateException("menus.yml: " + place + " has an unknown style " + style);
    List<Section> sections = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    Map<String, Integer> sectionOf = new LinkedHashMap<>();
    for (Object value : list(raw.get("sections"), place + ".sections")) {
      var section = map(value, place + ".section");
      List<Item> items = new ArrayList<>();
      for (Object entry : list(section.get("items"), place + ".items")) {
        var item = map(entry, place + ".item");
        String key = text(item, "key");
        int price = item.get("price") instanceof Integer n ? n : -1;
        if (price < 0 || price > MAX_PRICE) throw new IllegalStateException("menus.yml: " + place + "." + key + " has a bad price");
        if (!keys.add(key)) throw new IllegalStateException("menus.yml: " + place + " has " + key + " twice");
        items.add(new Item(key, text(item, "name"), price, text(item, "about"), text(item, "hint"), Boolean.TRUE.equals(item.get("pour"))));
        sectionOf.put(key, sections.size());
      }
      if (items.isEmpty()) throw new IllegalStateException("menus.yml: " + place + " has an empty section");
      sections.add(new Section(text(section, "name"), List.copyOf(items)));
    }
    if (sections.isEmpty()) throw new IllegalStateException("menus.yml: " + place + " has no sections");
    List<Pairing> pairings = new ArrayList<>();
    for (Object value : list(raw.get("pairings"), place + ".pairings")) {
      var pairing = map(value, place + ".pairing");
      String a = text(pairing, "a");
      String b = text(pairing, "b");
      // One item from each section, so a pairing must span two.
      if (!sectionOf.containsKey(a) || !sectionOf.containsKey(b) || sectionOf.get(a).equals(sectionOf.get(b))) {
        throw new IllegalStateException("menus.yml: " + place + " pairs " + a + " and " + b);
      }
      pairings.add(new Pairing(a, b, text(pairing, "line")));
    }
    return new Menu(place, style, text(raw, "enter"), text(raw, "board"), text(raw, "note"), text(raw, "payment"),
        List.copyOf(sections), List.copyOf(pairings));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value, String where) {
    if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("menus.yml: " + where + " is not a map");
    return (Map<String, Object>) map;
  }

  private static List<?> list(Object value, String where) {
    if (!(value instanceof List<?> list)) throw new IllegalStateException("menus.yml: " + where + " is not a list");
    return list;
  }

  private static String text(Map<String, Object> map, String key) {
    if (!(map.get(key) instanceof String text) || text.isBlank()) throw new IllegalStateException("menus.yml: missing " + key);
    return text;
  }
}
