package com.yuki.rubinco.octarine.ingest;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import com.yuki.rubinco.worldprogress.service.WorldImportRunLog;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Triggers only; work runs on its own thread so the shared scheduler is never held by a parse. */
@Component
@RequiredArgsConstructor
public class CoreKeeperImportRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(CoreKeeperImportRunner.class);
  private final CoreKeeperImportProperties properties;
  private final CoreKeeperImportService importService;
  private final WorldImportRunLog runLog;
  private final ServerActivityGate gate;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "corekeeper-import");
    thread.setDaemon(true);
    return thread;
  });

  @Override
  public void run(ApplicationArguments args) {
    int interrupted = runLog.failInterrupted(CoreKeeperImportService.GAME_ID);
    if (interrupted > 0) log.warn("Marked interrupted Core Keeper imports. count={}", interrupted);
  }

  @Scheduled(fixedDelayString = "${app.corekeeper.import-interval:10m}",
      initialDelayString = "${app.corekeeper.import-initial-delay:1m}")
  public void scheduledImport() {
    if (!properties.isImportEnabled()
        || !gate.importAllowed("corekeeper-import", CoreKeeperImportService.GAME_ID)) {
      return;
    }
    submit(() -> {
      importService.runScheduled().ifPresent(run ->
          log.info("Core Keeper scheduled import finished. runId={}, status={}, errorCode={}",
              run.getId(), run.getStatus(), run.getErrorCode()));
      runLog.purgeFinishedBefore(CoreKeeperImportService.GAME_ID,
          OffsetDateTime.now().minus(properties.getRunRetention()));
    });
  }

  /** Manual requests work even when scheduled imports are disabled. */
  @Scheduled(fixedDelayString = "${app.corekeeper.queue-poll-interval:15s}",
      initialDelayString = "${app.corekeeper.queue-poll-interval:15s}")
  public void queuedImports() {
    submit(() -> importService.runNextQueued().ifPresent(run ->
        log.info("Core Keeper manual import finished. runId={}, status={}, errorCode={}",
            run.getId(), run.getStatus(), run.getErrorCode())));
  }

  private void submit(Runnable task) {
    try {
      worker.execute(() -> {
        try {
          task.run();
        } catch (RuntimeException exception) {
          log.error("Core Keeper import task failed. failureType={}",
              exception.getClass().getSimpleName(), exception);
        }
      });
    } catch (RejectedExecutionException ignored) {
      // Shutting down.
    }
  }

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }
}
