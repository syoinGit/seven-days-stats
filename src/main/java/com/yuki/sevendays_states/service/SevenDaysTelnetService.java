package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import com.yuki.sevendays_states.entity.T_WorldTimeObservation;
import com.yuki.sevendays_states.repository.T_WorldTimeObservationRepository;
import com.yuki.sevendays_states.util.Hashing;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.telnet.TelnetClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SevenDaysTelnetService {

  private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
  private static final Pattern WORLD_TIME = Pattern.compile("^Day\\s+(?<day>\\d+)\\s*,?\\s*(?<hour>\\d{1,2}):(?<minute>\\d{2})$");

  private final SevenDaysDataProperties properties;
  private final GameLogImportService logImportService;
  private final T_WorldTimeObservationRepository worldTimeRepository;

  public GameLogImportResult fetchPlayerList() {
    TelnetSnapshot snapshot = executeSnapshotCommands();
    List<String> lines = snapshot.lpLines();
    snapshot.worldTime().ifPresent(this::saveWorldTime);
    if (lines.isEmpty()) {
      log.info("7DTD telnet lp returned no readable lines.");
      return new GameLogImportResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    log.debug("7DTD telnet lp normalized lines. count={}", lines.size());
    return logImportService.importLogLines("telnet:lp", lines);
  }

  private TelnetSnapshot executeSnapshotCommands() {
    SevenDaysTelnetConnectionLock.lock();
    List<String> lines = new ArrayList<>();
    WorldTime worldTime = null;
    LocalDateTime commandTime = LocalDateTime.now(ZoneOffset.UTC);
    TelnetClient telnet = new TelnetClient();
    telnet.setConnectTimeout(Math.toIntExact(properties.telnet().readTimeout().toMillis()));
    try {
      telnet.connect(properties.telnet().host(), properties.telnet().port());
      telnet.setSoTimeout(Math.toIntExact(properties.telnet().readTimeout().toMillis()));
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(telnet.getInputStream(), StandardCharsets.UTF_8));
      BufferedWriter writer = new BufferedWriter(
          new OutputStreamWriter(telnet.getOutputStream(), StandardCharsets.UTF_8));

      if (!properties.telnet().password().isBlank()) {
        drainPrompt(reader);
        writeTelnetCommand(writer, properties.telnet().password());
        drainPrompt(reader);
      }
      writeTelnetCommand(writer, "lp");

      String line;
      boolean commandOutputStarted = false;
      while ((line = reader.readLine()) != null) {
        if ("lp".equals(line)) {
          continue;
        }
        if (line.contains("Executing command 'lp'")) {
          commandOutputStarted = true;
        }
        if (!commandOutputStarted && (isPlayerLine(line) || isTotalLine(line))) {
          commandOutputStarted = true;
          lines.add(syntheticCommandLine(commandTime));
        }
        if (commandOutputStarted) {
          lines.add(normalizeCommandLine(line, commandTime));
        }
        if (commandOutputStarted && isTotalLine(line)) {
          break;
        }
      }

      writeTelnetCommand(writer, "gettime");
      while ((line = reader.readLine()) != null) {
        worldTime = parseWorldTime(line, commandTime).orElse(null);
        if (worldTime != null) {
          break;
        }
      }
    } catch (SocketTimeoutException e) {
      if (lines.isEmpty()) {
        log.warn("7DTD telnet lp command timed out before readable output. host={}, port={}",
            properties.telnet().host(), properties.telnet().port(), e);
      } else {
        log.debug("7DTD telnet lp command timed out after partial output. lines={}", lines.size());
      }
    } catch (Exception e) {
      log.warn("7DTD telnet lp command failed. host={}, port={}", properties.telnet().host(), properties.telnet().port(), e);
      return new TelnetSnapshot(List.of(), Optional.empty());
    } finally {
      try {
        telnet.disconnect();
      } catch (Exception e) {
        log.debug("7DTD telnet disconnect failed.", e);
      }
      SevenDaysTelnetConnectionLock.unlock();
    }
    return new TelnetSnapshot(List.copyOf(lines), Optional.ofNullable(worldTime));
  }

  static Optional<WorldTime> parseWorldTime(String rawLine, LocalDateTime commandTime) {
    String line = rawLine == null ? "" : rawLine.strip();
    int marker = line.lastIndexOf("Day ");
    if (marker >= 0) {
      line = line.substring(marker);
    }
    Matcher matcher = WORLD_TIME.matcher(line);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    int day = Integer.parseInt(matcher.group("day"));
    int hour = Integer.parseInt(matcher.group("hour"));
    int minute = Integer.parseInt(matcher.group("minute"));
    if (day < 1 || hour > 23 || minute > 59) {
      return Optional.empty();
    }
    return Optional.of(new WorldTime(
        commandTime.atOffset(ZoneOffset.UTC), day, hour, minute, line));
  }

  private void saveWorldTime(WorldTime worldTime) {
    String hash = Hashing.sha256("telnet:gettime|" + worldTime.observedAt() + "|" + worldTime.rawResponse());
    if (worldTimeRepository.existsBySourceHash(hash)) {
      return;
    }
    T_WorldTimeObservation observation = new T_WorldTimeObservation();
    observation.setObservedAt(worldTime.observedAt());
    observation.setGameDay(worldTime.day());
    observation.setGameHour(worldTime.hour());
    observation.setGameMinute(worldTime.minute());
    observation.setSource("telnet:gettime");
    observation.setSourceHash(hash);
    observation.setRawResponse(worldTime.rawResponse());
    worldTimeRepository.save(observation);
  }

  private void drainPrompt(BufferedReader reader) {
    try {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
      while (System.nanoTime() < deadline && reader.ready()) {
        reader.read();
      }
    } catch (Exception e) {
      log.debug("7DTD telnet prompt drain skipped.", e);
    }
  }

  private void writeTelnetCommand(BufferedWriter writer, String command) throws java.io.IOException {
    writer.write(command);
    writer.write("\r\n");
    writer.flush();
  }

  static List<String> normalizeLpOutput(List<String> rawLines, LocalDateTime commandTime) {
    List<String> lines = new ArrayList<>();
    boolean commandOutputStarted = false;
    for (String line : rawLines) {
      if ("lp".equals(line)) {
        continue;
      }
      if (line.contains("Executing command 'lp'")) {
        commandOutputStarted = true;
      }
      if (!commandOutputStarted && (isPlayerLine(line) || isTotalLine(line))) {
        commandOutputStarted = true;
        lines.add(syntheticCommandLine(commandTime));
      }
      if (commandOutputStarted) {
        lines.add(normalizeCommandLine(line, commandTime));
      }
      if (commandOutputStarted && isTotalLine(line)) {
        break;
      }
    }
    return List.copyOf(lines);
  }

  private static String normalizeCommandLine(String line, LocalDateTime commandTime) {
    if (!line.contains("Executing command 'lp'") || looksLikeGameLogLine(line)) {
      return line;
    }
    return syntheticCommandLine(commandTime);
  }

  private static String syntheticCommandLine(LocalDateTime commandTime) {
    return LOG_TIME.format(commandTime) + " 0.000 INF Executing command 'lp' by Telnet from app";
  }

  private static boolean looksLikeGameLogLine(String line) {
    return line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\s+\\d+(?:\\.\\d+)?\\s+[A-Z]+\\s+.*$");
  }

  private static boolean isPlayerLine(String line) {
    return line.matches("^\\d+\\. id=.*$");
  }

  private static boolean isTotalLine(String line) {
    return line.startsWith("Total of ") && line.endsWith(" in the game");
  }

  private record TelnetSnapshot(List<String> lpLines, Optional<WorldTime> worldTime) {
  }

  record WorldTime(OffsetDateTime observedAt, int day, int hour, int minute, String rawResponse) {
  }
}
