package com.yuki.rubinco.luminite.ingest;

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
public class TerrariaImportRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(TerrariaImportRunner.class);
  private final TerrariaImportProperties properties;
  private final TerrariaImportService importService;
  private final WorldImportRunLog runLog;
  private final ServerActivityGate gate;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "terraria-import");
    thread.setDaemon(true);
    return thread;
  });

  @Override
  public void run(ApplicationArguments args) {
    int interrupted = runLog.failInterrupted(TerrariaImportService.GAME_ID);
    if (interrupted > 0) log.warn("Marked interrupted Terraria imports. count={}", interrupted);
  }

  @Scheduled(fixedDelayString = "${app.terraria.import-interval:5m}",
      initialDelayString = "${app.terraria.import-initial-delay:1m}")
  public void scheduledImport() {
    if (!properties.isImportEnabled()
        || !gate.importAllowed("terraria-import", TerrariaImportService.GAME_ID)) {
      return;
    }
    submit(() -> {
      importService.runScheduled().ifPresent(run ->
          log.info("Terraria scheduled import finished. runId={}, status={}, errorCode={}",
              run.getId(), run.getStatus(), run.getErrorCode()));
      runLog.purgeFinishedBefore(TerrariaImportService.GAME_ID,
          OffsetDateTime.now().minus(properties.getRunRetention()));
    });
  }

  /** Manual requests work even when scheduled imports are disabled. */
  @Scheduled(fixedDelayString = "${app.terraria.queue-poll-interval:15s}",
      initialDelayString = "${app.terraria.queue-poll-interval:15s}")
  public void queuedImports() {
    submit(() -> importService.runNextQueued().ifPresent(run ->
        log.info("Terraria manual import finished. runId={}, status={}, errorCode={}",
            run.getId(), run.getStatus(), run.getErrorCode())));
  }

  private void submit(Runnable task) {
    try {
      worker.execute(() -> {
        try {
          task.run();
        } catch (RuntimeException exception) {
          log.error("Terraria import task failed. failureType={}",
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
