package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.SleeperLogEvent;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses both sleeper spawn and restore records into the common sleeper event model. */
public class SleeperLogParser {
  private static final Pattern SPAWN = Pattern.compile(
      "^(?:\\d+(?:\\.\\d+)\\s+)?SleeperVolume (?<svx>-?\\d+), (?<svy>-?\\d+), (?<svz>-?\\d+): "
          + "Spawning (?<x>-?\\d+), (?<y>-?\\d+), (?<z>-?\\d+) \\((?<chunkX>-?\\d+), (?<chunkZ>-?\\d+)\\), "
          + "group '(?<group>[^']*)', class (?<entityClass>[^,]+), count (?<count>-?\\d+)$");
  private static final Pattern RESTORE = Pattern.compile(
      "^(?:\\d+(?:\\.\\d+)\\s+)?SleeperVolume (?<svx>-?\\d+), (?<svy>-?\\d+), (?<svz>-?\\d+): "
          + "Restoring (?<x>-?\\d+), (?<y>-?\\d+), (?<z>-?\\d+) \\((?<chunkX>-?\\d+), (?<chunkZ>-?\\d+)\\) "
          + "'(?<entityClass>[^']+)', count (?<count>-?\\d+)$");

  private final GameLogLineParser lineParser;

  public SleeperLogParser() {
    this(new GameLogLineParser());
  }

  public SleeperLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public boolean matches(String rawLine) {
    return parse(rawLine).isPresent();
  }

  public Optional<SleeperLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<SleeperLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher spawn = SPAWN.matcher(line.message());
      if (spawn.matches()) {
        return Optional.of(toEvent(line, spawn, "SLEEPER_SPAWN", spawn.group("group")));
      }
      Matcher restore = RESTORE.matcher(line.message());
      if (restore.matches()) {
        return Optional.of(toEvent(line, restore, "SLEEPER_RESTORE", null));
      }
    } catch (RuntimeException ignored) {
      // malformed sleeper records are ignored by the import pipeline
    }
    return Optional.empty();
  }

  private SleeperLogEvent toEvent(
      ParsedLogLine line, Matcher matcher, String transactionType, String group) {
    return new SleeperLogEvent(
        line.occurredAt(),
        transactionType,
        integer(matcher, "svx"),
        integer(matcher, "svy"),
        integer(matcher, "svz"),
        integer(matcher, "x"),
        integer(matcher, "y"),
        integer(matcher, "z"),
        integer(matcher, "chunkX"),
        integer(matcher, "chunkZ"),
        group,
        matcher.group("entityClass"),
        integer(matcher, "count"),
        line.rawLine());
  }

  private int integer(Matcher matcher, String group) {
    return Integer.parseInt(matcher.group(group));
  }
}
