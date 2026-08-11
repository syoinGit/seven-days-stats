package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.telnet.TelnetClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SevenDaysTelnetCommandClient {
  private final SevenDaysDataProperties properties;

  /** Sends a server-wide chat message after removing command-breaking characters. */
  public boolean broadcast(String message) {
    if (message == null) {
      return false;
    }
    String sanitized = message
        // Bedrock responses are strings, but may contain newlines or Unicode line separators.
        // 7DTD's say command must receive the whole message as one line, like status notices.
        .replaceAll("\\s+", " ")
        .replaceAll("[\\p{Cntrl}]+", " ")
        .replace("\\", "\\\\")
        .replace("\"", "'")
        .strip();
    return !sanitized.isBlank() && send("say \"" + sanitized + "\"");
  }

  public boolean send(String command) {
    if (command == null || command.isBlank()) {
      return false;
    }
    TelnetClient telnet = new TelnetClient();
    try {
      telnet.setConnectTimeout(Math.toIntExact(properties.telnet().readTimeout().toMillis()));
      telnet.connect(properties.telnet().host(), properties.telnet().port());
      telnet.setSoTimeout(Math.toIntExact(properties.telnet().readTimeout().toMillis()));
      BufferedReader reader = new BufferedReader(new InputStreamReader(
          telnet.getInputStream(), StandardCharsets.UTF_8));
      BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
          telnet.getOutputStream(), StandardCharsets.UTF_8));
      if (!properties.telnet().password().isBlank()) {
        drain(reader);
        write(writer, properties.telnet().password());
        drain(reader);
      }
      write(writer, command);
      // Keep the session open until 7DTD acknowledges command execution. A flush only hands
      // bytes to the socket; disconnecting after a short drain can race with the game loop.
      return awaitCommandExecution(reader, command);
    } catch (Exception e) {
      log.warn("7DTD telnet command failed.", e);
      return false;
    } finally {
      try {
        telnet.disconnect();
      } catch (Exception ignored) {
        // best effort cleanup
      }
    }
  }

  private void write(BufferedWriter writer, String command) throws java.io.IOException {
    writer.write(command);
    writer.write("\r\n");
    writer.flush();
  }

  private void drain(BufferedReader reader) {
    try {
      long deadline = System.nanoTime() + 300_000_000L;
      while (System.nanoTime() < deadline) {
        while (reader.ready()) {
          if (reader.read() == -1) {
            return;
          }
        }
        Thread.sleep(10L);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
      // prompt output is optional
    }
  }

  private boolean awaitCommandExecution(BufferedReader reader, String command) {
    String commandName = command.strip().split("\\s+", 2)[0];
    long deadline = System.nanoTime()
        + Math.min(properties.telnet().readTimeout().toNanos(), 5_000_000_000L);
    try {
      while (System.nanoTime() < deadline) {
        if (reader.ready()) {
          String line = reader.readLine();
          if (line == null) {
            return false;
          }
          if (line.contains("Executing command '" + commandName + "'")) {
            return true;
          }
        } else {
          Thread.sleep(20L);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
      // The caller logs the failed command; avoid leaking Telnet protocol details here.
    }
    log.warn("7DTD telnet command was not acknowledged. command={}", commandName);
    return false;
  }
}
