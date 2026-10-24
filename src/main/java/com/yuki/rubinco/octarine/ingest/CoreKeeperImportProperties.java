package com.yuki.rubinco.octarine.ingest;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.corekeeper")
public class CoreKeeperImportProperties {
  /** Read-only path to the live save. The app never writes or locks it. */
  @NotNull
  private Path worldFile = Path.of("corekeeper/server-data/worlds/0.world.gzip");

  private boolean importEnabled;

  /** Object name table generated from the game (docs/OCTARINE.md); kept out of the repository. */
  private Path namesDir;

  @NotNull
  private Duration importInterval = Duration.ofMinutes(10);

  @NotNull
  private DataSize maxCompressedSize = DataSize.ofMegabytes(64);

  @NotNull
  private DataSize maxDecompressedSize = DataSize.ofMegabytes(256);

  @NotNull
  private Duration parseTimeout = Duration.ofSeconds(60);

  @Min(1)
  @Max(10)
  private int readAttempts = 3;

  @NotNull
  private Duration retryDelay = Duration.ofSeconds(3);

  /** A file modified more recently than this may still be written by the game. */
  @NotNull
  private Duration quietPeriod = Duration.ofSeconds(5);

  @NotNull
  private Duration runRetention = Duration.ofDays(90);

  @AssertTrue(message = "Core Keeper import interval must be at least one minute")
  public boolean isImportIntervalValid() {
    return importInterval != null && importInterval.compareTo(Duration.ofMinutes(1)) >= 0;
  }

  @AssertTrue(message = "Core Keeper size limits must be positive and decompressed >= compressed")
  public boolean isSizeLimitValid() {
    return maxCompressedSize != null && maxDecompressedSize != null
        && maxCompressedSize.toBytes() > 0
        && maxDecompressedSize.toBytes() >= maxCompressedSize.toBytes()
        && maxDecompressedSize.toBytes() <= DataSize.ofGigabytes(1).toBytes();
  }

  /** Show the data as stale after three missed checks. */
  public Duration staleAfter() {
    return importInterval.multipliedBy(3);
  }
}
