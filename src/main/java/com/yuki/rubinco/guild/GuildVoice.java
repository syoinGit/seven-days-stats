package com.yuki.rubinco.guild;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * How each resident talks (guild/voices.yml). The voice follows from who they are, in this order: a child (under 15)
 * or an elder (60 and over), a samurai of Tsukikage, words in their personality (shy, blunt, polite, cheerful, gentle),
 * an occupation that speaks politely, and otherwise one of the everyday voices for their gender, fixed by their id.
 * A first person named in the personality (一人称は「あたし」) wins over the voice's own. Nothing is stored.
 */
@Component
public class GuildVoice {
  public static final List<String> PLACES = List.of("counter", "tables", "board", "hearth");
  private static final Pattern FIRST = Pattern.compile("一人称[はがを]?「([^」]{1,6})」");
  private static final List<String[]> WORDS = List.of(
      new String[] {"shy", "内気|人見知り|臆病|控えめ|引っ込み思案|口下手|小心"},
      new String[] {"stern", "ぶっきらぼう|無愛想|短気|気が強|頑固|皮肉|毒舌|荒っぽ|口が悪|無骨|寡黙"},
      new String[] {"polite", "丁寧|礼儀|几帳面|生真面目|律儀|敬語|上品"},
      new String[] {"cheerful", "明る|陽気|元気|お調子|快活|人懐っこ|楽天|おしゃべり|社交的"},
      new String[] {"gentle", "穏やか|物静か|優し|温和|おっとり|柔和|のんびり|面倒見"});
  /** The faction by key (interactions) or by name (the room). */
  private static final java.util.Set<String> SAMURAI = java.util.Set.of("tsukikage_samurai", "月影武家衆");
  private static final Pattern POLITE_JOB = Pattern.compile("商人|神官|司祭|修道|僧|巫女|執事|侍女|学者|書記|医者|薬師");

  public record Voice(String key, String group, String first, String endings, String manner) {
    /** For the writer and the character page: 一人称「あたし」・語尾「〜だろ・〜さ」・さばさばして… */
    public String describe() {
      return "一人称「" + first + "」・語尾「" + endings + "」・" + manner;
    }
  }

  private final String version;
  private final Map<String, Voice> voices;
  private final Map<String, Map<String, List<String>>> idle;

  public GuildVoice() {
    try (InputStream in = new ClassPathResource("guild/voices.yml").getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      version = String.valueOf(root.get("version"));
      Map<String, Voice> loaded = new LinkedHashMap<>();
      map(root.get("voices")).forEach((key, value) -> {
        var m = map(value);
        loaded.put(key, new Voice(key, text(m, "group"), text(m, "first"), text(m, "endings"), text(m, "manner")));
      });
      Map<String, Map<String, List<String>>> lines = new LinkedHashMap<>();
      map(root.get("idle")).forEach((group, places) -> {
        Map<String, List<String>> byPlace = new LinkedHashMap<>();
        map(places).forEach((place, said) -> byPlace.put(place, ((List<?>) said).stream().map(String::valueOf).toList()));
        lines.put(group, byPlace);
      });
      voices = Map.copyOf(loaded);
      idle = Map.copyOf(lines);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    for (Voice voice : voices.values()) {
      var places = idle.get(voice.group());
      if (places == null || !places.keySet().containsAll(PLACES) || places.values().stream().anyMatch(List::isEmpty)) {
        throw new IllegalStateException("Voice group without idle lines for every place: " + voice.group());
      }
    }
  }

  String version() {
    return version;
  }

  Map<String, Voice> voices() {
    return voices;
  }

  public Voice of(long id, Integer age, String gender, String occupation, String personality, String faction) {
    boolean woman = "女性".equals(gender);
    boolean man = "男性".equals(gender);
    String text = personality == null ? "" : personality;
    String key;
    if (age != null && age < 15) key = woman ? "kid_f" : "kid_m";
    else if (age != null && age >= 60 && (woman || man)) key = woman ? "elder_f" : "elder_m";
    else if (SAMURAI.contains(faction == null ? "" : faction) && (woman || man)) key = woman ? "samurai_f" : "samurai_m";
    else {
      String trait = WORDS.stream().filter(word -> Pattern.compile(word[1]).matcher(text).find()).map(word -> word[0]).findFirst()
          .orElse(occupation != null && POLITE_JOB.matcher(occupation).find() ? "polite" : null);
      key = switch (trait == null ? "" : trait) {
        case "shy" -> woman ? "shy_f" : man ? "shy_m" : "neutral";
        case "stern" -> woman ? "brisk_f" : man ? "stern_m" : "neutral";
        case "polite" -> "polite";
        case "cheerful" -> woman ? "cheerful_f" : man ? "cheerful_m" : "neutral";
        case "gentle" -> woman ? "gentle_f" : man ? "gentle_m" : "neutral";
        default -> {
          List<String> everyday = woman ? List.of("cheerful_f", "gentle_f", "brisk_f") : man ? List.of("cheerful_m", "gentle_m", "stern_m") : List.of("neutral");
          yield everyday.get((int) Math.floorMod(id * 0x9E3779B97F4A7C15L >>> 17, (long) everyday.size()));
        }
      };
    }
    Voice voice = voices.get(key);
    var named = FIRST.matcher(text);
    return named.find() ? new Voice(voice.key(), voice.group(), named.group(1), voice.endings(), voice.manner()) : voice;
  }

  public Voice of(GuildService.Character character) {
    return of(character.id(), character.age(), character.gender(), character.occupation(), character.personality(), character.faction());
  }

  Voice of(GuildInteractionService.Person person) {
    return of(person.id(), person.age(), person.gender(), person.occupation(), person.personality(), person.affiliation().faction());
  }

  /** Said to no one in particular at a place; changes with the day and the part of the day. */
  public String idle(GuildService.Character character, String place, LocalDate date, String phase) {
    var lines = idle.get(of(character).group()).get(place);
    long mix = character.id() * 0xC2B2AE3D27D4EB4FL ^ date.toEpochDay() * 0x165667B19E3779F9L ^ phase.hashCode();
    return "「" + lines.get((int) Math.floorMod(mix ^ mix >>> 29, (long) lines.size())) + "」";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Invalid voices.yml");
    return (Map<String, Object>) map;
  }

  private static String text(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null || String.valueOf(value).isBlank()) throw new IllegalStateException("voices.yml missing " + key);
    return String.valueOf(value);
  }
}
