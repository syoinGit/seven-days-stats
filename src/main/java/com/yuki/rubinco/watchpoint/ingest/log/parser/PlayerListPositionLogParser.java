package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.PlayerListPositionLogEvent;
import com.yuki.rubinco.watchpoint.ingest.log.dto.PlayerListPositionLogEvent.PlayerPosition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerListPositionLogParser {

  private static final String COMMAND_PREFIX = "Executing command 'lp' by Telnet";
  private static final Pattern PLAYER = Pattern.compile(
      "^\\d+\\. id=(?<entityId>\\d+), (?<playerName>.*), pos=\\("
          + "(?<x>-?\\d+(?:\\.\\d+)?),\\s*"
          + "(?<y>-?\\d+(?:\\.\\d+)?),\\s*"
          + "(?<z>-?\\d+(?:\\.\\d+)?)\\), rot=\\("
          + "(?<rotationX>-?\\d+(?:\\.\\d+)?),\\s*"
          + "(?<rotationY>-?\\d+(?:\\.\\d+)?),\\s*"
          + "(?<rotationZ>-?\\d+(?:\\.\\d+)?)\\),\\s*"
          + "remote=(?:True|False), health=(?<health>-?\\d+), deaths=(?<deaths>-?\\d+), "
          + "zombies=(?<zombies>-?\\d+), players=(?<players>-?\\d+), score=(?<score>-?\\d+), "
          + "level=(?<level>-?\\d+), pltfmid=(?<platformId>[^,]+), crossid=(?<crossPlatformId>[^,]+), "
          + "ip=[^,]+, ping=(?<ping>-?\\d+).*$");
  private static final Pattern TOTAL = Pattern.compile("^Total of (?<total>\\d+) in the game$");

  private final GameLogLineParser lineParser;

  public PlayerListPositionLogParser() {
    this(new GameLogLineParser());
  }

  public PlayerListPositionLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public Optional<PlayerListPositionLogEvent> parse(List<String> lines, int startIndex) {
    if (startIndex < 0 || startIndex >= lines.size()) {
      return Optional.empty();
    }
    Optional<ParsedLogLine> commandLine = lineParser.parse(lines.get(startIndex));
    if (commandLine.isEmpty() || !commandLine.get().message().startsWith(COMMAND_PREFIX)) {
      return Optional.empty();
    }

    List<PlayerPosition> players = new ArrayList<>();
    int consumed = 1;
    Integer totalPlayerCount = null;
    for (int i = startIndex + 1; i < lines.size(); i++) {
      String rawLine = lines.get(i);
      if (lineParser.parse(rawLine).isPresent()) {
        break;
      }
      consumed++;
      Matcher totalMatcher = TOTAL.matcher(rawLine);
      if (totalMatcher.matches()) {
        totalPlayerCount = Integer.parseInt(totalMatcher.group("total"));
        break;
      }
      Matcher matcher = PLAYER.matcher(rawLine);
      if (!matcher.matches()) {
        continue;
      }
      players.add(new PlayerPosition(
          matcher.group("playerName"),
          Integer.parseInt(matcher.group("entityId")),
          parsePosition(matcher.group("x")),
          parsePosition(matcher.group("y")),
          parsePosition(matcher.group("z")),
          new BigDecimal(matcher.group("rotationX")),
          new BigDecimal(matcher.group("rotationY")),
          new BigDecimal(matcher.group("rotationZ")),
          Integer.parseInt(matcher.group("health")),
          Integer.parseInt(matcher.group("deaths")),
          Integer.parseInt(matcher.group("zombies")),
          Integer.parseInt(matcher.group("players")),
          Integer.parseInt(matcher.group("score")),
          Integer.parseInt(matcher.group("level")),
          matcher.group("platformId"),
          matcher.group("crossPlatformId"),
          Integer.parseInt(matcher.group("ping")),
          rawLine));
    }

    // Only a complete, unambiguous snapshot may decide which players are offline.
    if (totalPlayerCount == null || totalPlayerCount != players.size()
        || players.stream().map(PlayerPosition::playerEntityId).distinct().count() != players.size()) {
      return Optional.empty();
    }
    return Optional.of(new PlayerListPositionLogEvent(
        commandLine.get().occurredAt(),
        List.copyOf(players),
        totalPlayerCount,
        List.copyOf(lines.subList(startIndex, startIndex + consumed)),
        consumed));
  }

  private static int parsePosition(String value) {
    return Math.toIntExact(Math.round(Double.parseDouble(value)));
  }
}
