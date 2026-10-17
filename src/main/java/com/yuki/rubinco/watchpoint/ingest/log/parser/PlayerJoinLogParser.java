package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.PlayerJoinLogEvent;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerJoinLogParser {

  private static final Pattern EVENT = Pattern.compile(
      "^PlayerSpawnedInWorld \\(reason: (?<reason>[^,]+), position: (?<x>-?\\d+), (?<y>-?\\d+), (?<z>-?\\d+)\\): "
          + "EntityID=(?<entityId>-?\\d+), PltfmId='(?<platformId>[^']*)', CrossId='(?<crossId>[^']*)', "
          + "OwnerID='[^']*', PlayerName='(?<playerName>.*)', ClientNumber='(?<clientNumber>-?\\d+)'$");

  private final GameLogLineParser lineParser;

  public PlayerJoinLogParser() {
    this(new GameLogLineParser());
  }

  public PlayerJoinLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public boolean matches(String rawLine) {
    return parse(rawLine).isPresent();
  }

  public Optional<PlayerJoinLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<PlayerJoinLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher matcher = EVENT.matcher(line.message());
      if (!matcher.matches()) {
        return Optional.empty();
      }
      return Optional.of(new PlayerJoinLogEvent(
          line.occurredAt(),
          matcher.group("playerName"),
          integer(matcher.group("entityId")),
          matcher.group("platformId"),
          matcher.group("crossId"),
          integer(matcher.group("x")),
          integer(matcher.group("y")),
          integer(matcher.group("z")),
          matcher.group("reason"),
          integer(matcher.group("clientNumber")),
          line.rawLine()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private int integer(String value) {
    return Integer.parseInt(value);
  }
}
