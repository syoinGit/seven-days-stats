package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.WorldEventLogEvent;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BloodMoonLogParser {

  private static final Pattern EVENT = Pattern.compile(
      "^BloodMoon SetDay: day (?<day>\\d+), last day (?<lastDay>\\d+), freq (?<freq>\\d+), range (?<range>\\d+)$");

  private final GameLogLineParser lineParser;

  public BloodMoonLogParser() {
    this(new GameLogLineParser());
  }

  public BloodMoonLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public Optional<WorldEventLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<WorldEventLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher matcher = EVENT.matcher(line.message());
      if (!matcher.matches()) {
        return Optional.empty();
      }
      return Optional.of(new WorldEventLogEvent(
          line.occurredAt(),
          "BLOOD_MOON",
          null,
          null,
          "Day " + matcher.group("day") + " / 周期 " + matcher.group("freq"),
          null,
          null,
          null,
          null,
          null,
          null,
          line.rawLine()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
