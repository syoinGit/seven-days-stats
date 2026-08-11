package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerLogStreamingService implements SmartLifecycle {

  private final SevenDaysDataProperties properties;
  private final GameLogImportService logImportService;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "7dtd-docker-log-stream");
    thread.setDaemon(true);
    return thread;
  });

  private volatile Future<?> task;
  private volatile Process process;

  @Override
  public void start() {
    if (!shouldStart() || !running.compareAndSet(false, true)) {
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

  private boolean shouldStart() {
    return "docker".equalsIgnoreCase(properties.mode());
  }

  private void streamLoop() {
    String sourceName = "docker:" + properties.docker().containerName();
    while (running.get()) {
      GameLogImportService.StreamSession session = logImportService.openStreamSession(sourceName);
      try {
        ProcessBuilder builder = new ProcessBuilder(List.of(
            "docker",
            "logs",
            "-f",
            "--since",
            properties.docker().logSince(),
            properties.docker().containerName()));
        builder.redirectErrorStream(true);
        process = builder.start();
        log.info("7DTD docker log stream started. container={}", properties.docker().containerName());
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while (running.get() && (line = reader.readLine()) != null) {
            session.acceptLine(line);
          }
        }
        session.flush();
        int exitCode = process.waitFor();
        log.warn("7DTD docker log stream exited. container={}, exitCode={}",
            properties.docker().containerName(), exitCode);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        log.warn("7DTD docker log stream failed. container={}", properties.docker().containerName(), e);
      } finally {
        destroyProcess();
      }
      sleepBeforeReconnect();
    }
  }

  private void sleepBeforeReconnect() {
    try {
      TimeUnit.MILLISECONDS.sleep(properties.docker().reconnectDelay().toMillis());
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
