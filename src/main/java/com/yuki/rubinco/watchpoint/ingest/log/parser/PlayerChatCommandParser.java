package com.yuki.rubinco.watchpoint.ingest.log.parser;

import com.yuki.rubinco.watchpoint.ingest.log.dto.ParsedLogLine;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerChatCommandParser {
  private static final Pattern[] PATTERNS = {
      Pattern.compile("^Chat\\s*\\([^)]*\\)?:?\\s*(?<name>[^:]+):\\s*(?<message>![^\\s]+).*$"),
      Pattern.compile("^PlayerChat: (?<name>[^:]+):\\s*(?<message>![^\\s]+).*$"),
      Pattern.compile("^GMSG: (?<name>[^:]+):\\s*(?<message>![^\\s]+).*$")
  };

  public Optional<ChatCommand> parse(ParsedLogLine line) {
    for (Pattern pattern : PATTERNS) {
      Matcher matcher = pattern.matcher(line.message());
      if (matcher.matches()) {
        return Optional.of(new ChatCommand(
            matcher.group("name").strip(), matcher.group("message").strip()));
      }
    }
    return Optional.empty();
  }

  public record ChatCommand(String playerName, String command) {
  }
}
