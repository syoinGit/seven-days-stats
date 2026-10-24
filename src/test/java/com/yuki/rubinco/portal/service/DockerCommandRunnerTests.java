package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuki.rubinco.portal.config.DockerGameServerProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerCommandRunnerTests {
  @TempDir Path directory;

  @Test
  void usesNonInteractiveSudoAndOnlyTheFixedHelperWithoutApplicationEnvironment() throws Exception {
    var runner = runner("""
        printf '%s\n' "${HOME:-unset}" "${DOCKER_HOST:-unset}" "$@"
        """);
    assertThat(runner.run("start", "7dtd"))
        .isEqualTo("unset\nunset\n-n\n--\n/usr/local/sbin/rubinco-game-control\nstart\n7dtd");
  }

  @Test
  void stopUsesItsOwnTimeoutAndOnlyManualGames() throws Exception {
    var properties = properties("sleep 0.3; printf '%s' \"$4 $5\"\n");
    properties.setCommandTimeout(Duration.ofMillis(100));
    var runner = new DockerCommandRunner(properties);
    assertThat(runner.run(Duration.ofSeconds(5), "stop", "minecraft")).isEqualTo("stop minecraft");
    for (String id : java.util.List.of("terraria", "seven-days-postgres", "7dtd --time 0")) {
      assertThatThrownBy(() -> runner.run(Duration.ofSeconds(5), "stop", id))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void failureDoesNotExposeCommandOutput() throws Exception {
    var runner = runner("echo private-daemon-detail; exit 1\n");
    assertThatThrownBy(() -> runner.run("status", "7dtd"))
        .isInstanceOf(IllegalStateException.class).hasMessage("Docker command failed");
  }

  @Test
  void timesOutInsteadOfHoldingTheRequestIndefinitely() throws Exception {
    var properties = properties("sleep 30\n");
    properties.setCommandTimeout(Duration.ofMillis(100));
    var runner = new DockerCommandRunner(properties);
    assertThatThrownBy(() -> runner.run("start", "7dtd"))
        .isInstanceOf(IllegalStateException.class).hasMessage("Docker command timed out");
  }

  @Test
  void rejectsOversizedOutput() throws Exception {
    var runner = runner("head -c 17000 /dev/zero\n");
    assertThatThrownBy(() -> runner.run("status", "7dtd"))
        .hasMessage("Docker command output exceeded its limit");
  }

  private DockerCommandRunner runner(String body) throws Exception {
    return new DockerCommandRunner(properties(body));
  }

  private DockerGameServerProperties properties(String body) throws Exception {
    Path executable = directory.resolve("fake-docker");
    Files.writeString(executable, "#!/bin/sh\n" + body);
    assertThat(executable.toFile().setExecutable(true, true)).isTrue();
    var properties = new DockerGameServerProperties();
    properties.setSudoExecutable(executable.toString());
    return properties;
  }

  @Test
  void rejectsArbitraryDockerOperationsFlagsAndUnknownTargetsBeforeExecutingAnything() throws Exception {
    var runner = runner("echo this-must-not-run; exit 99\n");
    for (String[] arguments : java.util.List.of(new String[] {"start", "terraria"},
        new String[] {"start", "seven-days-postgres"}, new String[] {"start", "7dtd; echo injected"},
        new String[] {"start", "7dtd", "--attach"}, new String[] {"run", "alpine"},
        new String[] {"inspect", "7dtd"}, new String[] {"logs", "terraria"}, new String[] {"statuses", "--help"})) {
      assertThatThrownBy(() -> runner.run(arguments)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> runner.logStream("seven-days-postgres"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void logStreamingUsesTheSameRestrictedHelper() {
    var command = new DockerCommandRunner(new DockerGameServerProperties()).logStream("7dtd");
    assertThat(command.command()).containsExactly("/usr/bin/sudo", "-n", "--",
        "/usr/local/sbin/rubinco-game-control", "logs", "7dtd");
    assertThat(command.environment()).containsOnlyKeys("PATH");
  }
}
