package com.yuki.rubinco.watchpoint.ingest;

import com.yuki.rubinco.watchpoint.persistence.entity.T_ImportRun;
import com.yuki.rubinco.watchpoint.persistence.repository.T_ImportRunRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Import audit records commit independently so failed imports remain diagnosable. */
@Service
@RequiredArgsConstructor
public class DataImportRunService {

  private final SevenDaysDataProperties properties;
  private final T_ImportRunRepository importRunRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public T_ImportRun start() {
    T_ImportRun run = new T_ImportRun();
    run.setStartedAt(LocalDateTime.now());
    run.setEnvironmentName(properties.environmentName());
    run.setSourceRoot(properties.root().toString());
    run.setConfigDir(properties.configPath().toString());
    run.setDataDir(properties.dataPath().toString());
    run.setGameDir(properties.gamePath().toString());
    run.setStatus("RUNNING");
    return importRunRepository.save(run);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(Long runId, String status, String message) {
    T_ImportRun run = importRunRepository.findById(runId).orElseThrow();
    run.setFinishedAt(LocalDateTime.now());
    run.setStatus(status);
    run.setMessage(message);
    importRunRepository.save(run);
  }

}
