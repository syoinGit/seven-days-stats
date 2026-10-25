package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes a copy of the real helper with only its Docker binary replaced by a recorder. */
class GameControlHelperTests {
  @TempDir Path directory;
  private Path helper;
  private Path record;
  private Path docker;

  @BeforeEach
  void setUp() throws Exception {
    record = directory.resolve("arguments");
    docker = directory.resolve("fake-docker");
    Files.writeString(docker, "#!/bin/sh\n"
        + "printf '%s\\n' \"$HOME\" \"${DOCKER_HOST:-unset}\" \"${DOCKER_CONFIG:-unset}\" \"$@\" > "
        + quote(record) + "\nprintf '%s\\n' '{\"Status\":\"exited\"}'\n");
    assertThat(docker.toFile().setExecutable(true, true)).isTrue();
    helper = directory.resolve("game-control");
    Files.writeString(helper, Files.readString(Path.of("infra/docker/rubinco-game-control"))
        .replace("/usr/bin/docker", quote(docker)));
    assertThat(helper.toFile().setExecutable(true, true)).isTrue();
  }

  @Test
  void fixedGameIdsResolveToTheirRealContainerNames() throws Exception {
    for (var target : Map.of("7dtd", "7dtd", "corekeeper", "rubinco-corekeeper",
        "minecraft", "rubinco-minecraft").entrySet()) {
      assertThat(execute(Map.of(), "start", target.getKey())).isZero();
      assertThat(Files.readAllLines(record)).containsExactly("/root", "unset", "unset",
          "--host=unix:///var/run/docker.sock", "start", target.getValue());
    }
    for (var target : Map.of("7dtd", "7dtd", "corekeeper", "rubinco-corekeeper",
        "minecraft", "rubinco-minecraft").entrySet()) {
      assertThat(execute(Map.of(), "stop", target.getKey())).isZero();
      assertThat(Files.readAllLines(record)).containsExactly("/root", "unset", "unset",
          "--host=unix:///var/run/docker.sock", "stop", "--time", "120", target.getValue());
    }
    assertThat(execute(Map.of(), "status", "terraria")).isZero();
    assertThat(Files.readAllLines(record)).endsWith("inspect", "--type", "container", "--format",
        "{{json .State}}", "terraria");
  }

  @Test
  void helperIndependentlyRejectsUnknownTargetsExtraArgumentsAndDockerCommands() throws Exception {
    for (String[] arguments : List.of(new String[] {}, new String[] {"start", "terraria"},
        new String[] {"start", "seven-days-postgres"}, new String[] {"start", "7dtd", "--attach"},
        new String[] {"start", "7dtd; touch injected"}, new String[] {"status", "--help"},
        new String[] {"status", "7dtd", "extra"}, new String[] {"statuses", "extra"},
        new String[] {"logs", "terraria"}, new String[] {"logs", "7dtd", "--since", "24h"},
        new String[] {"logs", "rubinco-corekeeper"}, new String[] {"logs", "corekeeper", "--since", "24h"},
        new String[] {"run", "alpine"}, new String[] {"stop", "terraria"},
        new String[] {"stop", "seven-days-postgres"}, new String[] {"stop", "7dtd", "--time", "0"},
        new String[] {"exec", "7dtd", "sh"})) {
      assertThat(execute(Map.of(), arguments)).isEqualTo(64);
      assertThat(record).doesNotExist();
    }
  }

  @Test
  void ignoresDockerOverridesAndBashStartupCodeFromTheCaller() throws Exception {
    Path injected = directory.resolve("injected");
    Path startup = directory.resolve("bash-env");
    Files.writeString(startup, "touch " + quote(injected) + "\n");
    assertThat(execute(Map.of("BASH_ENV", startup.toString(), "DOCKER_HOST", "tcp://attacker:2375",
        "DOCKER_CONFIG", directory.toString()), "status", "7dtd")).isZero();
    assertThat(injected).doesNotExist();
    assertThat(Files.readAllLines(record)).startsWith("/root", "unset", "unset",
        "--host=unix:///var/run/docker.sock");
  }

  @Test
  void logStreamsAreLimitedToFixedGamesAndOptions() throws Exception {
    assertThat(execute(Map.of(), "logs", "7dtd")).isZero();
    assertThat(Files.readAllLines(record)).endsWith("logs", "--follow", "--since", "5m", "7dtd");
    assertThat(execute(Map.of(), "logs", "corekeeper")).isZero();
    assertThat(Files.readAllLines(record)).endsWith(
        "logs", "--follow", "--timestamps", "--since", "1h", "rubinco-corekeeper");
  }

  @Test
  void statusListUsesOnlyTheFourConfiguredGames() throws Exception {
    Path output = directory.resolve("statuses");
    var process = new ProcessBuilder(helper.toString(), "statuses")
        .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    assertThat(process.waitFor()).isZero();
    assertThat(Files.readAllLines(output)).containsExactly(
        "7dtd {\"Status\":\"exited\"}", "terraria {\"Status\":\"exited\"}",
        "corekeeper {\"Status\":\"exited\"}", "minecraft {\"Status\":\"exited\"}");
  }

  @Test
  void failedContainerDoesNotHideLaterHealthyContainers() throws Exception {
    Files.writeString(docker, "#!/bin/sh\n"
        + "for argument in \"$@\"; do\n"
        + "  if [ \"$argument\" = '7dtd' ]; then exit 1; fi\n"
        + "done\n"
        + "printf '%s\\n' '{\"Status\":\"running\"}'\n");
    Path output = directory.resolve("statuses");
    var process = new ProcessBuilder(helper.toString(), "statuses")
        .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    assertThat(process.waitFor()).isZero();
    assertThat(Files.readAllLines(output)).containsExactly(
        "7dtd {\"Status\":\"unavailable\"}", "terraria {\"Status\":\"running\"}",
        "corekeeper {\"Status\":\"running\"}", "minecraft {\"Status\":\"running\"}");
  }

  @Test
  void unavailableDockerStillFailsTheDeploymentPreflight() throws Exception {
    Files.writeString(docker, "#!/bin/sh\nexit 1\n");
    assertThat(execute(Map.of(), "statuses")).isEqualTo(1);
  }

  private int execute(Map<String, String> environment, String... arguments) throws Exception {
    var command = new ArrayList<>(List.of(helper.toString()));
    command.addAll(List.of(arguments));
    var builder = new ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().putAll(environment);
    return builder.start().waitFor();
  }

  private String quote(Path path) {
    return "'" + path.toString().replace("'", "'\\''") + "'";
  }
}
