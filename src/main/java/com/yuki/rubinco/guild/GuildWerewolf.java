package com.yuki.rubinco.guild;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A solo werewolf game played with the residents (/guild/werewolf), without AI: the traveller sits in with a few of
 * them, the roles are dealt in the browser and the residents argue, vote and hunt by rules (guild.js is not involved;
 * static/js/guild-werewolf.js). Here only who sits in and what each of them can say (guild/werewolf.yml, in their own
 * voice) are chosen. Nothing of a game is sent back or kept.
 */
@Component
public class GuildWerewolf {
  /** What a resident can say, in the order the game asks for them. */
  public static final List<String> KINDS = List.of("greet", "co", "white", "black", "counter", "suspect", "defend", "vote");
  /** The residents who play with the traveller: fewer than MIN and there is no game. */
  public static final int MIN = 4;
  public static final int MAX = 7;

  /**
   * A resident at the table, with their lines ({me} already filled in, {t} left for the game). A kind with several
   * wordings has them one per line; the game picks one each time.
   */
  public record Player(GuildService.Character character, Map<String, String> lines) { }

  private final GuildService guild;
  private final GuildVoice voices;
  private final String version;
  private final Map<String, Map<String, List<String>>> lines;

  public GuildWerewolf(GuildService guild, GuildVoice voices) {
    this.guild = guild;
    this.voices = voices;
    try (InputStream in = new ClassPathResource("guild/werewolf.yml").getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      version = String.valueOf(root.get("version"));
      Map<String, Map<String, List<String>>> read = new LinkedHashMap<>();
      map(root.get("lines"), "lines").forEach((group, value) -> {
        var said = map(value, group);
        Map<String, List<String>> kinds = new LinkedHashMap<>();
        for (String kind : KINDS) {
          List<?> wordings = said.get(kind) instanceof List<?> list ? list : said.get(kind) == null ? List.of() : List.of(said.get(kind));
          if (wordings.isEmpty()) throw new IllegalStateException("werewolf.yml: " + group + " has no " + kind);
          List<String> texts = new ArrayList<>();
          for (Object wording : wordings) {
            if (!(wording instanceof String text) || text.isBlank() || text.contains("\n")) throw new IllegalStateException("werewolf.yml: " + group + "." + kind + " is not a line");
            // Every line about someone names them; the claims and the defence are in the speaker's own words.
            boolean about = !kind.equals("greet") && !kind.equals("co") && !kind.equals("counter") && !kind.equals("defend");
            if (about != text.contains("{t}")) throw new IllegalStateException("werewolf.yml: " + group + "." + kind + " and {t}");
            texts.add(text);
          }
          kinds.put(kind, List.copyOf(texts));
        }
        read.put(group, Map.copyOf(kinds));
      });
      lines = Map.copyOf(read);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    for (var voice : voices.voices().values()) {
      if (!lines.containsKey(voice.group())) throw new IllegalStateException("werewolf.yml: no lines for " + voice.group());
    }
  }

  String version() {
    return version;
  }

  /** What this resident says, in their voice (the wordings of a kind one per line). */
  public Map<String, String> lines(GuildService.Character character) {
    var voice = voices.of(character);
    Map<String, String> out = new LinkedHashMap<>();
    lines.get(voice.group()).forEach((kind, texts) -> out.put(kind, String.join("\n", texts).replace("{me}", voice.first())));
    return out;
  }

  /**
   * Who sits in tonight: those in the guild today first, then other living residents, up to MAX, in a new order each
   * time. Empty when fewer than MIN are alive.
   */
  public List<Player> table(ZonedDateTime now) {
    var random = ThreadLocalRandom.current();
    List<GuildService.Character> here = new ArrayList<>(guild.room(now).visits().stream().map(GuildService.Visit::character).toList());
    Collections.shuffle(here, random);
    List<GuildService.Character> others = new ArrayList<>(guild.characters(0).stream().filter(character -> character.status().equals("ALIVE")).toList());
    Collections.shuffle(others, random);
    Map<Long, GuildService.Character> picked = new LinkedHashMap<>();
    for (var character : Stream.concat(here.stream(), others.stream()).toList()) {
      if (picked.size() == MAX) break;
      picked.putIfAbsent(character.id(), character);
    }
    if (picked.size() < MIN) return List.of();
    List<GuildService.Character> seated = new ArrayList<>(picked.values());
    Collections.shuffle(seated, random);
    return seated.stream().map(character -> new Player(character, lines(character))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value, String where) {
    if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("werewolf.yml: " + where + " is not a map");
    return (Map<String, Object>) map;
  }
}
