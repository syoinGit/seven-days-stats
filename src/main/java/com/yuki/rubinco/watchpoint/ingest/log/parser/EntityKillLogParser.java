package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.EntityKillLogEvent;
import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EntityKillLogParser {

  private static final Pattern EVENT = Pattern.compile(
      "^Entity (?<targetEntityType>\\S+) (?<targetEntityId>-?\\d+) killed by (?<playerName>.+) (?<playerEntityId>-?\\d+)$");

  private final GameLogLineParser lineParser;

  public EntityKillLogParser() {
    this(new GameLogLineParser());
  }

  public EntityKillLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public boolean matches(String rawLine) {
    return parse(rawLine).isPresent();
  }

  public Optional<EntityKillLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<EntityKillLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher matcher = EVENT.matcher(line.message());
      if (!matcher.matches()) {
        return Optional.empty();
      }
      return Optional.of(new EntityKillLogEvent(
          line.occurredAt(),
          matcher.group("playerName"),
          integer(matcher.group("playerEntityId")),
          matcher.group("targetEntityType"),
          integer(matcher.group("targetEntityId")),
          line.rawLine()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private int integer(String value) {
    return Integer.parseInt(value);
  }
}
