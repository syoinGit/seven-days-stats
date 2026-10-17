package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import com.yuki.rubinco.watchpoint.ingest.log.dto.VehicleLogEvent;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VehicleLogParser {

  private static final Pattern WRITE = Pattern.compile(
      "^\\d+ VehicleManager write #\\d+, id (?<vehicleId>\\d+), (?<vehicleType>[^,]+), "
          + "\\((?<x>-?\\d+(?:\\.\\d+)?), (?<y>-?\\d+(?:\\.\\d+)?), (?<z>-?\\d+(?:\\.\\d+)?)\\), chunk -?\\d+, -?\\d+$");
  private static final Pattern LOADED = Pattern.compile(
      "^VehicleManager loaded #\\d+, id (?<vehicleId>\\d+), \\[type=(?<vehicleType>[^,]+), name=(?<vehicleName>[^,]+), id=\\d+], "
          + "\\((?<x>-?\\d+(?:\\.\\d+)?), (?<y>-?\\d+(?:\\.\\d+)?), (?<z>-?\\d+(?:\\.\\d+)?)\\), "
          + "chunk -?\\d+, -?\\d+ \\(-?\\d+, -?\\d+\\), owner (?<owner>\\S+)$");
  private static final Pattern POST_INIT = Pattern.compile(
      "^Vehicle PostInit \\[type=(?<vehicleType>[^,]+), name=(?<vehicleName>[^,]+), id=(?<vehicleId>\\d+)], "
          + "\\((?<x>-?\\d+(?:\\.\\d+)?), (?<y>-?\\d+(?:\\.\\d+)?), (?<z>-?\\d+(?:\\.\\d+)?)\\).*$");
  private static final Pattern REMOVED = Pattern.compile(
      "^VehicleManager RemoveTrackedVehicle \\[type=(?<vehicleType>[^,]+), name=(?<vehicleName>[^,]+), id=(?<vehicleId>\\d+)], (?<reason>.+)$");

  private final GameLogLineParser lineParser;

  public VehicleLogParser() {
    this(new GameLogLineParser());
  }

  public VehicleLogParser(GameLogLineParser lineParser) {
    this.lineParser = lineParser;
  }

  public Optional<VehicleLogEvent> parse(String rawLine) {
    return lineParser.parse(rawLine).flatMap(this::parse);
  }

  public Optional<VehicleLogEvent> parse(ParsedLogLine line) {
    try {
      Matcher loaded = LOADED.matcher(line.message());
      if (loaded.matches()) {
        return Optional.of(positionEvent(line, loaded, "VEHICLE_LOADED", loaded.group("owner")));
      }
      Matcher write = WRITE.matcher(line.message());
      if (write.matches()) {
        return Optional.of(positionEvent(line, write, "VEHICLE_WRITE", null));
      }
      Matcher postInit = POST_INIT.matcher(line.message());
      if (postInit.matches()) {
        return Optional.of(positionEvent(line, postInit, "VEHICLE_POST_INIT", null));
      }
      Matcher removed = REMOVED.matcher(line.message());
      if (removed.matches()) {
        return Optional.of(new VehicleLogEvent(
            line.occurredAt(),
            "VEHICLE_REMOVED",
            Integer.parseInt(removed.group("vehicleId")),
            removed.group("vehicleType"),
            removed.group("vehicleName"),
            null,
            null,
            null,
            null,
            removed.group("reason"),
            line.rawLine()));
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private VehicleLogEvent positionEvent(
      ParsedLogLine line,
      Matcher matcher,
      String eventType,
      String ownerCrossPlatformId) {
    String vehicleName = namedGroup(matcher, "vehicleName").orElse(matcher.group("vehicleType"));
    return new VehicleLogEvent(
        line.occurredAt(),
        eventType,
        Integer.parseInt(matcher.group("vehicleId")),
        matcher.group("vehicleType"),
        vehicleName,
        ownerCrossPlatformId,
        coordinate(matcher.group("x")),
        coordinate(matcher.group("y")),
        coordinate(matcher.group("z")),
        null,
        line.rawLine());
  }

  private Optional<String> namedGroup(Matcher matcher, String name) {
    try {
      return Optional.ofNullable(matcher.group(name));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private Integer coordinate(String value) {
    return new BigDecimal(value).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
  }
}
