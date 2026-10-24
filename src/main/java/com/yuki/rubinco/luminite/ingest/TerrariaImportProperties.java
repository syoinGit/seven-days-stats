package com.yuki.rubinco.luminite.ingest;

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
@ConfigurationProperties(prefix = "app.terraria")
public class TerrariaImportProperties {
  /** Read-only path to the live .wld. Not guessed: while unset, imports fail with a clear reason. */
  private Path worldFile;

  private boolean importEnabled;

  /** Generated name tables (items.tsv, npcs.tsv, prefixes.tsv, containers.tsv). */
  private Path namesDir;

  @NotNull
  private Duration importInterval = Duration.ofMinutes(5);

  @NotNull
  private DataSize maxFileSize = DataSize.ofMegabytes(64);

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

  @AssertTrue(message = "Terraria import interval must be at least one minute")
  public boolean isImportIntervalValid() {
    return importInterval != null && importInterval.compareTo(Duration.ofMinutes(1)) >= 0;
  }

  @AssertTrue(message = "Terraria max file size must be between 1MB and 1GB")
  public boolean isMaxFileSizeValid() {
    return maxFileSize != null && maxFileSize.toBytes() >= DataSize.ofMegabytes(1).toBytes()
        && maxFileSize.toBytes() <= DataSize.ofGigabytes(1).toBytes();
  }

  /** Blank environment values mean "not configured", never the working directory. */
  public void setWorldFile(Path worldFile) {
    this.worldFile = blankToNull(worldFile);
  }

  public void setNamesDir(Path namesDir) {
    this.namesDir = blankToNull(namesDir);
  }

  private static Path blankToNull(Path path) {
    return path == null || path.toString().isBlank() ? null : path;
  }

  /** Show the data as stale after three missed checks. */
  public Duration staleAfter() {
    return importInterval.multipliedBy(3);
  }
}
