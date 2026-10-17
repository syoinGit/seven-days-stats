package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameLogLineParser {

  private static final Pattern LINE = Pattern.compile(
      "^(?<occurredAt>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\s+"
          + "(?<serverUptimeSeconds>\\d+(?:\\.\\d+)?)\\s+"
          + "(?<level>[A-Z]+)\\s+"
          + "(?<message>.*)$");

  private final ZoneId zoneId;

  public GameLogLineParser() {
    this(ZoneOffset.UTC);
  }

  public GameLogLineParser(ZoneId zoneId) {
    this.zoneId = zoneId;
  }

  public Optional<ParsedLogLine> parse(String rawLine) {
    try {
      Matcher matcher = LINE.matcher(rawLine);
      if (!matcher.matches()) {
        return Optional.empty();
      }
      return Optional.of(new ParsedLogLine(
          LocalDateTime.parse(matcher.group("occurredAt")).atZone(zoneId).toOffsetDateTime(),
          new BigDecimal(matcher.group("serverUptimeSeconds")),
          matcher.group("level"),
          matcher.group("message"),
          rawLine));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
