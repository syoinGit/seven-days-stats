package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.WorldEventLogEvent;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiDirectorLogParser {

  private static final Pattern WANDERING_TARGET = Pattern.compile(
      "^AIDirector: FindWanderingTargets at player '\\[type=EntityPlayer, name=(?<playerName>.+), id=(?<entityId>\\d+)]', dist (?<distance>\\d+(?:\\.\\d+)?)$");
  private static final Pattern SCOUTS = Pattern.compile(
      "^AIDirector: Spawning Scouts\\d* at \\((?<x>-?\\d+(?:\\.\\d+)?), (?<y>-?\\d+(?:\\.\\d+)?), (?<z>-?\\d+(?:\\.\\d+)?)\\), "
          + "to \\((?<targetX>-?\\d+(?:\\.\\d+)?), (?<targetY>-?\\d+(?:\\.\\d+)?), (?<targetZ>-?\\d+(?:\\.\\d+)?)\\)$");
  private static final Pattern SCREAMER = Pattern.compile(
      "^Spawned \\[type=EntityZombie, name=zombieScreamer, id=(?<entityId>\\d+)] at "
          + "\\((?<x>-?\\d+(?:\\.\\d+)?), (?<y>-?\\d+(?:\\.\\d+)?), (?<z>-?\\d+(?:\\.\\d+)?)\\) "
          + "Day=(?<day>\\d+) TotalInWave=(?<total>\\d+) CurrentWave=(?<wave>\\d+)$");

  private final GameLogLineParser lineParser;

  public AiDirectorLogParser() {
    this(new GameLogLineParser());
  }

  public AiDirectorLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public Optional<WorldEventLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<WorldEventLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher wanderingTarget = WANDERING_TARGET.matcher(line.message());
      if (wanderingTarget.matches()) {
        return Optional.of(new WorldEventLogEvent(
            line.occurredAt(),
            "WANDERING_HORDE",
            wanderingTarget.group("playerName"),
            Integer.parseInt(wanderingTarget.group("entityId")),
            "距離 " + wanderingTarget.group("distance"),
            null,
            null,
            null,
            null,
            null,
            null,
            line.rawLine()));
      }
      Matcher scouts = SCOUTS.matcher(line.message());
      if (scouts.matches()) {
        return Optional.of(new WorldEventLogEvent(
            line.occurredAt(),
            "SCOUT_HORDE",
            null,
            null,
            "スクリーマー",
            coordinate(scouts.group("x")),
            coordinate(scouts.group("y")),
            coordinate(scouts.group("z")),
            coordinate(scouts.group("targetX")),
            coordinate(scouts.group("targetY")),
            coordinate(scouts.group("targetZ")),
            line.rawLine()));
      }
      Matcher screamer = SCREAMER.matcher(line.message());
      if (screamer.matches()) {
        return Optional.of(new WorldEventLogEvent(
            line.occurredAt(),
            "SCREAMER_SPAWN",
            null,
            null,
            "Day " + screamer.group("day"),
            coordinate(screamer.group("x")),
            coordinate(screamer.group("y")),
            coordinate(screamer.group("z")),
            null,
            null,
            null,
            line.rawLine()));
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Integer coordinate(String value) {
    return new BigDecimal(value).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
  }
}
