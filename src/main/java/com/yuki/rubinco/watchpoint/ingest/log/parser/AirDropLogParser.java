package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.WorldEventLogEvent;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AirDropLogParser {

  private static final Pattern SUPPLY_CRATE = Pattern.compile(
      "^AIAirDrop: Spawned supply crate at \\((?<x>-?\\d+(?:\\.\\d+)?), (?<y>-?\\d+(?:\\.\\d+)?), (?<z>-?\\d+(?:\\.\\d+)?)\\), "
          + "plane is at \\((?<planeX>-?\\d+(?:\\.\\d+)?), (?<planeY>-?\\d+(?:\\.\\d+)?), (?<planeZ>-?\\d+(?:\\.\\d+)?)\\)$");

  private final GameLogLineParser lineParser;

  public AirDropLogParser() {
    this(new GameLogLineParser());
  }

  public AirDropLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public Optional<WorldEventLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<WorldEventLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher matcher = SUPPLY_CRATE.matcher(line.message());
      if (!matcher.matches()) {
        return Optional.empty();
      }
      return Optional.of(new WorldEventLogEvent(
          line.occurredAt(),
          "AIR_DROP",
          null,
          null,
          "補給物資",
          coordinate(matcher.group("x")),
          coordinate(matcher.group("y")),
          coordinate(matcher.group("z")),
          coordinate(matcher.group("planeX")),
          coordinate(matcher.group("planeY")),
          coordinate(matcher.group("planeZ")),
          line.rawLine()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Integer coordinate(String value) {
    return new BigDecimal(value).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
  }
}
