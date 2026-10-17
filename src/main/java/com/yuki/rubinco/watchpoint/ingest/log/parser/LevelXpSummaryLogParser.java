package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.LevelXpSummaryLogEvent;
import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LevelXpSummaryLogParser {

  private static final String HEADER = "MinEventLogMessage: XP gained during the last level:";
  private static final Pattern VALUE = Pattern.compile(
      "^CVarLogValue: \\$(?<key>xpFromLootThisLevel|xpFromHarvestingThisLevel|xpFromKillThisLevel) == (?<value>-?\\d+)$");

  private final GameLogLineParser lineParser;

  public LevelXpSummaryLogParser() {
    this(new GameLogLineParser());
  }

  public LevelXpSummaryLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public boolean matches(String rawLine) {
    return lineParser.parse(rawLine)
        .map(line -> HEADER.equals(line.message()))
        .orElse(false);
  }

  public Optional<LevelXpSummaryLogEvent> parse(List<String> lines, int startIndex) {
    if (startIndex < 0 || startIndex >= lines.size()) {
      return Optional.empty();
    }
    Optional<ParsedLogLine> header = lineParser.parse(lines.get(startIndex));
    if (header.isEmpty() || !HEADER.equals(header.get().message())) {
      return Optional.empty();
    }

    int xpFromLoot = 0;
    int xpFromHarvesting = 0;
    int xpFromKill = 0;
    int consumed = 1;
    int matchedValues = 0;

    for (int i = startIndex + 1; i < lines.size(); i++) {
      Optional<ParsedLogLine> valueLine = lineParser.parse(lines.get(i));
      if (valueLine.isEmpty()) {
        break;
      }
      Matcher matcher = VALUE.matcher(valueLine.get().message());
      if (!matcher.matches()) {
        break;
      }
      int value = Integer.parseInt(matcher.group("value"));
      switch (matcher.group("key")) {
        case "xpFromLootThisLevel" -> xpFromLoot = value;
        case "xpFromHarvestingThisLevel" -> xpFromHarvesting = value;
        case "xpFromKillThisLevel" -> xpFromKill = value;
        default -> {
        }
      }
      matchedValues++;
      consumed++;
    }

    if (matchedValues == 0) {
      return Optional.empty();
    }

    int xpTotal = xpFromLoot + xpFromHarvesting + xpFromKill;
    return Optional.of(new LevelXpSummaryLogEvent(
        header.get().occurredAt(),
        xpFromLoot,
        xpFromHarvesting,
        xpFromKill,
        xpTotal,
        List.copyOf(lines.subList(startIndex, startIndex + consumed)),
        consumed));
  }
}
