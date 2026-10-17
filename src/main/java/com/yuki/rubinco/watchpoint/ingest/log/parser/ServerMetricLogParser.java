package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.ServerMetricLogEvent;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerMetricLogParser {

  private static final Pattern EVENT = Pattern.compile(
      "^Time: (?<uptimeMinutes>\\d+(?:\\.\\d+)?)m FPS: (?<fps>\\d+(?:\\.\\d+)?) "
          + "Heap: (?<heapMb>\\d+(?:\\.\\d+)?)MB Max: (?<maxHeapMb>\\d+(?:\\.\\d+)?)MB "
          + "Chunks: (?<chunks>-?\\d+) CGO: (?<cgo>-?\\d+) Ply: (?<players>-?\\d+) "
          + "Zom: (?<zombies>-?\\d+) Ent: (?<entities>-?\\d+) \\((?<entityDetail>-?\\d+)\\) "
          + "Items: (?<items>-?\\d+) CO: (?<co>-?\\d+) RSS: (?<rssMb>\\d+(?:\\.\\d+)?)MB$");

  private final GameLogLineParser lineParser;

  public ServerMetricLogParser() {
    this(new GameLogLineParser());
  }

  public ServerMetricLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public boolean matches(String rawLine) {
    return parse(rawLine).isPresent();
  }

  public Optional<ServerMetricLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<ServerMetricLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher matcher = EVENT.matcher(line.message());
      if (!matcher.matches()) {
        return Optional.empty();
      }
      return Optional.of(new ServerMetricLogEvent(
          line.occurredAt(),
          decimal(matcher.group("uptimeMinutes")),
          decimal(matcher.group("fps")),
          decimal(matcher.group("heapMb")),
          decimal(matcher.group("maxHeapMb")),
          integer(matcher.group("chunks")),
          integer(matcher.group("cgo")),
          integer(matcher.group("players")),
          integer(matcher.group("zombies")),
          integer(matcher.group("entities")),
          integer(matcher.group("entityDetail")),
          integer(matcher.group("items")),
          integer(matcher.group("co")),
          decimal(matcher.group("rssMb")),
          line.rawLine()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private int integer(String value) {
    return Integer.parseInt(value);
  }
}
