package com.yuki.rubinco.octarine.session;

import com.yuki.rubinco.octarine.ingest.CoreKeeperImportProperties;
import com.yuki.rubinco.portal.service.DockerCommandRunner;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/**
 * Follows the Core Keeper server log through the restricted helper ({@code logs corekeeper}) and
 * records connections. Log lines are never stored or logged; only the parsed events are kept.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoreKeeperLogStreamingService implements SmartLifecycle {
  static final String HELPER_GAME = "corekeeper";

  private final CoreKeeperImportProperties properties;
  private final CoreKeeperSessionStore store;
  private final DockerCommandRunner dockerCommands;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "corekeeper-log-stream");
    thread.setDaemon(true);
    return thread;
  });

  private volatile Future<?> task;
  private volatile Process process;

  @Override
  public void start() {
    if (!properties.isLogStreamEnabled() || !running.compareAndSet(false, true)) {
      return;
    }
    task = executor.submit(this::streamLoop);
  }

  @Override
  public void stop() {
    running.set(false);
    destroyProcess();
    if (task != null) {
      task.cancel(true);
    }
    executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void streamLoop() {
    // Kept across reconnects: a player who joined over an hour ago is no longer in the replayed
    // part of the log, but their later disconnect still has to find their SteamID.
    CoreKeeperLogParser parser = new CoreKeeperLogParser();
    while (running.get()) {
      try {
        ProcessBuilder builder = dockerCommands.logStream(HELPER_GAME);
        builder.redirectErrorStream(true);
        process = builder.start();
        log.info("Core Keeper log stream started.");
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while (running.get() && (line = reader.readLine()) != null) {
            accept(parser, line);
          }
        }
        int exitCode = process.waitFor();
        log.warn("Core Keeper log stream exited. exitCode={}", exitCode);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        log.warn("Core Keeper log stream failed. failureType={}", e.getClass().getSimpleName());
      } finally {
        destroyProcess();
      }
      sleepBeforeReconnect();
    }
  }

  private void accept(CoreKeeperLogParser parser, String line) {
    parser.accept(line).ifPresent(event -> {
      try {
        store.apply(event);
      } catch (RuntimeException exception) {
        // Keep reading; one bad write must not stop the stream. The event holds a SteamID, so
        // only its type is logged.
        log.warn("Core Keeper session write failed. event={}, failureType={}",
            event.getClass().getSimpleName(), exception.getClass().getSimpleName());
      }
    });
  }

  private void sleepBeforeReconnect() {
    try {
      TimeUnit.MILLISECONDS.sleep(properties.getLogReconnectDelay().toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void destroyProcess() {
    Process current = process;
    process = null;
    if (current == null || !current.isAlive()) {
      return;
    }
    current.destroy();
    try {
      if (!current.waitFor(2, TimeUnit.SECONDS)) {
        current.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      current.destroyForcibly();
    }
  }
}
