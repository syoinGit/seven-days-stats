package com.yuki.rubinco.worldprogress.service;

import com.yuki.rubinco.worldprogress.entity.T_WorldImportRun;
import com.yuki.rubinco.worldprogress.repository.M_GameWorldRepository;
import com.yuki.rubinco.worldprogress.repository.T_WorldImportRunRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each state change commits on its own so a failed parse still leaves an audit row. */
@Service
@RequiredArgsConstructor
public class WorldImportRunLog {
  private static final List<String> OPEN = List.of("QUEUED", "RUNNING");
  private final T_WorldImportRunRepository runs;
  private final M_GameWorldRepository worlds;

  @Transactional
  public T_WorldImportRun startScheduled(String gameId) {
    T_WorldImportRun run = newRun(gameId, "SCHEDULED", "system", "RUNNING");
    run.setStartedAt(run.getRequestedAt());
    return runs.save(run);
  }

  /** Returns empty when a manual or running job already exists, so repeated clicks do not stack. */
  @Transactional
  public Optional<T_WorldImportRun> queueManual(String gameId, String requestedBy) {
    worlds.findByGameIdForUpdate(gameId);
    if (runs.existsByGameIdAndStatusIn(gameId, OPEN)) return Optional.empty();
    return Optional.of(runs.save(newRun(gameId, "MANUAL", requestedBy, "QUEUED")));
  }

  @Transactional
  public Optional<T_WorldImportRun> claimQueued(String gameId) {
    worlds.findByGameIdForUpdate(gameId);
    if (!runs.findByGameIdAndStatus(gameId, "RUNNING").isEmpty()) return Optional.empty();
    return runs.findFirstByGameIdAndStatusOrderByRequestedAtAscIdAsc(gameId, "QUEUED").map(run -> {
      run.setStatus("RUNNING");
      run.setStartedAt(OffsetDateTime.now());
      return runs.save(run);
    });
  }

  @Transactional
  public T_WorldImportRun finish(T_WorldImportRun run, String status, String errorCode,
      String errorMessage) {
    OffsetDateTime now = OffsetDateTime.now();
    run.setStatus(status);
    run.setFinishedAt(now);
    if (run.getStartedAt() != null) {
      run.setDurationMs(Duration.between(run.getStartedAt(), now).toMillis());
    }
    run.setErrorCode(errorCode);
    run.setErrorMessage(errorMessage == null ? null
        : errorMessage.substring(0, Math.min(errorMessage.length(), 500)));
    return runs.save(run);
  }

  /** A restart interrupts in-flight work; record it rather than leaving a run open forever. */
  @Transactional
  public int failInterrupted(String gameId) {
    List<T_WorldImportRun> running = runs.findByGameIdAndStatus(gameId, "RUNNING");
    running.forEach(run -> finish(run, "FAILED", "INTERRUPTED", "アプリの再起動により中断しました。"));
    return running.size();
  }

  @Transactional
  public int purgeFinishedBefore(String gameId, OffsetDateTime before) {
    return runs.deleteFinishedBefore(gameId, before);
  }

  private T_WorldImportRun newRun(String gameId, String trigger, String requestedBy, String status) {
    T_WorldImportRun run = new T_WorldImportRun();
    run.setGameId(gameId);
    run.setTriggerType(trigger);
    run.setStatus(status);
    run.setRequestedBy(requestedBy == null ? null
        : requestedBy.substring(0, Math.min(requestedBy.length(), 160)));
    run.setRequestedAt(OffsetDateTime.now());
    return run;
  }
}
