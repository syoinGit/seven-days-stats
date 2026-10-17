package com.yuki.rubinco.watchpoint.ingest;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits an import unit before its caller advances buffers or releases a running guard. */
@Service
public class ImportTransaction {

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public <T> T execute(Supplier<T> work) {
    return work.get();
  }
}
