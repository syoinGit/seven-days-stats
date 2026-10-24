package com.yuki.rubinco.portal.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.games.docker")
public class DockerGameServerProperties {
  @NotBlank
  private String sudoExecutable = "/usr/bin/sudo";

  @NotBlank
  private String controlExecutable = "/usr/local/sbin/rubinco-game-control";

  @NotNull
  private Duration commandTimeout = Duration.ofSeconds(20);

  @AssertTrue(message = "Docker command timeout must be between 1 and 60 seconds")
  public boolean isCommandTimeoutValid() {
    return commandTimeout != null && commandTimeout.compareTo(Duration.ofSeconds(1)) >= 0
        && commandTimeout.compareTo(Duration.ofSeconds(60)) <= 0;
  }
}
