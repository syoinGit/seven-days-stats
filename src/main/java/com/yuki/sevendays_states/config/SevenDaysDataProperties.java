package com.yuki.sevendays_states.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

@ConfigurationProperties(prefix = "app.sevendays")
public record SevenDaysDataProperties(
    String environmentName,
    String mode,
    Path root,
    Path configDir,
    @Name("import") Import importSettings,
    Log log,
    Docker docker,
    Telnet telnet,
    Transaction transaction,
    Poi poi
) {

  public SevenDaysDataProperties {
    environmentName = environmentName == null || environmentName.isBlank() ? "local" : environmentName;
    mode = mode == null || mode.isBlank() ? "file" : mode;
    root = root == null ? Path.of("7dtd") : root;
    configDir = blankToNull(configDir);
    importSettings = importSettings == null
        ? new Import(true, false, Duration.ofMinutes(10)) : importSettings;
    log = log == null ? new Log(null, false, Duration.ofMinutes(10),
        Duration.ofMinutes(10), Duration.ofMinutes(1)) : log;
    docker = docker == null ? new Docker("7dtd", "5m", Duration.ofSeconds(5)) : docker;
    telnet = telnet == null ? new Telnet("127.0.0.1", 8081, "", false,
        Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(15)) : telnet;
    transaction = transaction == null ? new Transaction(Duration.ofSeconds(120)) : transaction;
    poi = poi == null ? new Poi("classpath:poi-translations.csv") : poi;
  }

  public Path configPath() {
    return configDir == null ? root.resolve("config") : configDir;
  }

  public Path dataPath() {
    return root.resolve("data");
  }

  public Path gamePath() {
    return root.resolve("game");
  }

  public Path serverConfigPath() {
    return configPath().resolve("serverconfig.xml");
  }

  public Path generatedWorldsPath() {
    return dataPath().resolve("GeneratedWorlds");
  }

  public Path savesPath() {
    return dataPath().resolve("Saves");
  }

  public Path gameConfigPath() {
    return gamePath().resolve("Data").resolve("Config");
  }

  public Path gamePrefabsPath() {
    return gamePath().resolve("Data").resolve("Prefabs");
  }

  public Path logPath() {
    return log.dir() == null ? root.resolve("log") : log.dir();
  }

  private static Path blankToNull(Path path) {
    return path == null || path.toString().isBlank() ? null : path;
  }

  private static Duration positiveOrDefault(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  public record Import(boolean startupEnabled, boolean scheduledEnabled, Duration fixedDelay) {
    public Import {
      fixedDelay = positiveOrDefault(fixedDelay, Duration.ofMinutes(10));
    }
  }

  public record Log(
      Path dir,
      boolean scheduledEnabled,
      Duration fixedDelay,
      Duration initialDelay,
      Duration serverMetricInterval
  ) {
    public Log {
      dir = blankToNull(dir);
      fixedDelay = positiveOrDefault(fixedDelay, Duration.ofMinutes(10));
      initialDelay = initialDelay == null || initialDelay.isNegative()
          ? Duration.ofMinutes(10) : initialDelay;
      serverMetricInterval = positiveOrDefault(serverMetricInterval, Duration.ofMinutes(1));
    }
  }

  public record Docker(String containerName, String logSince, Duration reconnectDelay) {
    public Docker {
      containerName = containerName == null || containerName.isBlank() ? "7dtd" : containerName;
      logSince = logSince == null || logSince.isBlank() ? "5m" : logSince;
      reconnectDelay = positiveOrDefault(reconnectDelay, Duration.ofSeconds(5));
    }
  }

  public record Telnet(
      String host,
      int port,
      String password,
      boolean enabled,
      Duration initialDelay,
      Duration lpInterval,
      Duration readTimeout
  ) {
    public Telnet {
      host = host == null || host.isBlank() ? "127.0.0.1" : host;
      port = port <= 0 ? 8081 : port;
      password = password == null ? "" : password;
      initialDelay = initialDelay == null || initialDelay.isNegative()
          ? Duration.ofSeconds(30) : initialDelay;
      lpInterval = positiveOrDefault(lpInterval, Duration.ofSeconds(60));
      if (lpInterval.compareTo(Duration.ofSeconds(60)) < 0) {
        lpInterval = Duration.ofSeconds(60);
      }
      readTimeout = positiveOrDefault(readTimeout, Duration.ofSeconds(15));
    }
  }

  public record Transaction(Duration currentStateMaxAge) {
    public Transaction {
      currentStateMaxAge = positiveOrDefault(currentStateMaxAge, Duration.ofSeconds(120));
    }
  }

  public record Poi(String translationResource) {
    public Poi {
      translationResource = translationResource == null || translationResource.isBlank()
          ? "classpath:poi-translations.csv" : translationResource;
    }
  }
}
