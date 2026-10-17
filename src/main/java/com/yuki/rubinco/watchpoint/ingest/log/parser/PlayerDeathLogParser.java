package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.WorldEventLogEvent;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerDeathLogParser {

  private static final Pattern EVENT = Pattern.compile(
      "^GMSG: Player '(?<player>[^']+)' (?:(?:killed by '(?<killer>[^']+)')|died)$");

  private final GameLogLineParser lineParser;

  public PlayerDeathLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public Optional<WorldEventLogEvent> parse(ParsedLogLine line) {
    Matcher matcher = EVENT.matcher(line.message());
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String killer = matcher.group("killer");
    return Optional.of(new WorldEventLogEvent(
        line.occurredAt(), "PLAYER_DEATH", matcher.group("player"), null,
        killer == null ? null : killer, null, null, null, null, null, null, line.rawLine()));
  }
}
