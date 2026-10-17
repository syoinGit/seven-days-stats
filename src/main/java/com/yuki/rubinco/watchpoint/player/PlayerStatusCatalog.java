package com.yuki.rubinco.watchpoint.player;

import java.util.Locale;
import java.util.Map;

/** Canonical status codes, labels, and chat aliases used by the status use case. */
public final class PlayerStatusCatalog {

  private static final Map<String, String> LABELS = Map.of(
      "ACTIVE", "活動中",
      "EATING", "ごはん中",
      "AFK", "AFK",
      "OUT", "外出",
      "SLEEPING", "就寝中",
      "SOLO", "ソロ探索中");

  private static final Map<String, String> DISPLAY_LABELS = Map.of(
      "ACTIVE", "🟢 活動中",
      "EATING", "🍚 ごはん中",
      "AFK", "💤 AFK",
      "OUT", "🚗 外出",
      "SLEEPING", "🛏 就寝中",
      "SOLO", "🧭 ソロ探索中");

  private static final Map<String, String> CHAT_COMMANDS = Map.ofEntries(
      Map.entry("!飯", "EATING"),
      Map.entry("!めし", "EATING"),
      Map.entry("!ごはん", "EATING"),
      Map.entry("!afk", "AFK"),
      Map.entry("!戻り", "ACTIVE"),
      Map.entry("!もどり", "ACTIVE"),
      Map.entry("!back", "ACTIVE"),
      Map.entry("!寝る", "SLEEPING"),
      Map.entry("!ねる", "SLEEPING"),
      Map.entry("!外出", "OUT"),
      Map.entry("!そと", "OUT"),
      Map.entry("!ソロ", "SOLO"),
      Map.entry("!そろ", "SOLO"),
      Map.entry("!solo", "SOLO"));

  private PlayerStatusCatalog() {
  }

  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String status = raw.strip().toUpperCase(Locale.ROOT);
    return LABELS.containsKey(status) ? status : null;
  }

  public static String fromChatCommand(String command) {
    if (command == null) {
      return null;
    }
    return CHAT_COMMANDS.get(command.strip().toLowerCase(Locale.ROOT));
  }

  public static String label(String status) {
    return status == null ? null : LABELS.get(status);
  }

  public static String displayLabel(String status) {
    return status == null ? null : DISPLAY_LABELS.get(status);
  }
}
