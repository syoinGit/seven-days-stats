package com.yuki.rubinco.octarine.session;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads connections from the dedicated server's log, one stream at a time (not thread-safe).
 *
 * <p>Lines come from {@code docker logs --timestamps}, so each starts with Docker's UTC time. The
 * game names a connection by an internal user id; the SteamID64 appears on the lines before it:
 * <pre>
 * Accepted connection from 76561198000000000 with result OK awaiting authentication
 * SteamNet Warning: [#1000000001 P2P steamid:76561198000000000 vport 0] ...
 * Connected to userid:1000000001
 * [userid:1000000001] player Explorer connected islocalplayer=False
 * [userid:1000000001] is using new name Explorer
 * Disconnected from userid:1000000001 with reason App_Min
 * </pre>
 * Lines that do not match are ignored; a connection whose SteamID was not seen is skipped.
 */
public class CoreKeeperLogParser {
  static final int MAX_NAME_LENGTH = 120;

  private static final Pattern TIMESTAMPED = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\S+Z) (.*)$");
  private static final String STEAM_ID = "(7656119\\d{10})";
  private static final Pattern ACCEPTED =
      Pattern.compile("^Accepted connection from " + STEAM_ID + " with result OK\\b");
  private static final Pattern P2P = Pattern.compile("\\[#(\\d+) P2P steamid:" + STEAM_ID + "\\b");
  private static final Pattern CONNECTED_TO = Pattern.compile("^Connected to userid:(\\d+)\\s*$");
  private static final Pattern PLAYER_CONNECTED =
      Pattern.compile("^\\[userid:(\\d+)] player (.+) connected islocalplayer=(?:True|False)\\s*$");
  private static final Pattern RENAMED = Pattern.compile("^\\[userid:(\\d+)] is using new name (.+)$");
  private static final Pattern DISCONNECTED =
      Pattern.compile("^Disconnected from userid:(\\d+) with reason (\\w{1,40})");

  private final Map<String, String> steamIds = new HashMap<>();
  private String accepted;

  public sealed interface Event permits Connected, Renamed, Disconnected {
    String steamId();

    OffsetDateTime at();
  }

  public record Connected(String steamId, String name, OffsetDateTime at) implements Event { }

  public record Renamed(String steamId, String name, OffsetDateTime at) implements Event { }

  public record Disconnected(String steamId, String reason, OffsetDateTime at) implements Event { }

  public Optional<Event> accept(String line) {
    if (line == null) return Optional.empty();
    Matcher stamped = TIMESTAMPED.matcher(line);
    if (!stamped.matches()) return Optional.empty();
    OffsetDateTime at;
    try {
      at = Instant.parse(stamped.group(1)).atOffset(ZoneOffset.UTC);
    } catch (DateTimeParseException exception) {
      return Optional.empty();
    }
    String text = stamped.group(2);
    Matcher m;
    if ((m = ACCEPTED.matcher(text)).find()) {
      accepted = m.group(1);
    } else if ((m = P2P.matcher(text)).find()) {
      steamIds.put(m.group(1), m.group(2));
    } else if ((m = CONNECTED_TO.matcher(text)).matches()) {
      if (accepted != null) steamIds.put(m.group(1), accepted);
      accepted = null;
    } else if ((m = PLAYER_CONNECTED.matcher(text)).matches()) {
      String steamId = steamIds.get(m.group(1));
      String name = name(m.group(2));
      if (steamId != null && name != null) return Optional.of(new Connected(steamId, name, at));
    } else if ((m = RENAMED.matcher(text)).matches()) {
      String steamId = steamIds.get(m.group(1));
      String name = name(m.group(2));
      if (steamId != null && name != null) return Optional.of(new Renamed(steamId, name, at));
    } else if ((m = DISCONNECTED.matcher(text)).find()) {
      String steamId = steamIds.remove(m.group(1));
      if (steamId != null) return Optional.of(new Disconnected(steamId, m.group(2), at));
    }
    return Optional.empty();
  }

  /** Player-chosen text: control characters become spaces, blank is dropped, long is cut. */
  static String name(String raw) {
    String cleaned = raw.codePoints()
        .map(c -> Character.isISOControl(c) ? ' ' : c)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString().strip();
    if (cleaned.isEmpty()) return null;
    if (cleaned.length() <= MAX_NAME_LENGTH) return cleaned;
    int end = Character.isHighSurrogate(cleaned.charAt(MAX_NAME_LENGTH - 1)) ? MAX_NAME_LENGTH - 1 : MAX_NAME_LENGTH;
    return cleaned.substring(0, end);
  }
}
