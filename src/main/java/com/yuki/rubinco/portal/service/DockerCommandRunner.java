package com.yuki.rubinco.portal.service;

import com.yuki.rubinco.portal.config.DockerGameServerProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Calls only the root-owned, sudo-allowlisted helper; never accesses Docker directly. */
@Component
@RequiredArgsConstructor
public class DockerCommandRunner {
  private static final int MAX_OUTPUT_BYTES = 16 * 1024;
  private final DockerGameServerProperties properties;

  public String run(String... arguments) {
    Path output = null;
    Process process = null;
    try {
      output = Files.createTempFile("rubinco-docker-", ".out");
      ProcessBuilder builder = command(arguments)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .redirectOutput(output.toFile());
      process = builder.start();
      process.getOutputStream().close();
      if (!process.waitFor(properties.getCommandTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("Docker command timed out");
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("Docker command failed");
      }
      try (var stream = Files.newInputStream(output)) {
        byte[] bytes = stream.readNBytes(MAX_OUTPUT_BYTES + 1);
        if (bytes.length > MAX_OUTPUT_BYTES) {
          throw new IllegalStateException("Docker command output exceeded its limit");
        }
        return new String(bytes, StandardCharsets.UTF_8).strip();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Docker command interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Docker command unavailable", exception);
    } finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
      if (output != null) {
        try {
          Files.deleteIfExists(output);
        } catch (IOException ignored) {
          // A failed cleanup must not hide the command outcome.
        }
      }
    }
  }

  public ProcessBuilder logStream(String containerName) {
    if (!"7dtd".equals(containerName)) {
      throw new IllegalArgumentException("Only the 7dtd log stream is permitted");
    }
    return command("logs", "7dtd");
  }

  private ProcessBuilder command(String... arguments) {
    boolean permitted = arguments.length == 1 && "statuses".equals(arguments[0]);
    if (arguments.length == 2) {
      permitted = switch (arguments[0]) {
        case "status" -> Set.of("7dtd", "terraria", "corekeeper", "minecraft").contains(arguments[1]);
        case "start" -> Set.of("7dtd", "corekeeper", "minecraft").contains(arguments[1]);
        case "logs" -> "7dtd".equals(arguments[1]);
        default -> false;
      };
    }
    if (!permitted) throw new IllegalArgumentException("Game control operation is not permitted");
    List<String> command = new ArrayList<>(List.of(
        properties.getSudoExecutable(), "-n", "--", properties.getControlExecutable()));
    command.addAll(List.of(arguments));
    ProcessBuilder builder = new ProcessBuilder(command);
    // Do not send application passwords, AWS credentials, or Docker/shell overrides to sudo.
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/sbin:/usr/bin:/sbin:/bin");
    return builder;
  }
}
