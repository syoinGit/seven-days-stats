package com.yuki.rubinco.watchpoint.ingest;

import java.util.concurrent.locks.ReentrantLock;

/** Serializes Telnet sessions because 7DTD may not reliably process concurrent clients. */
final class SevenDaysTelnetConnectionLock {
  private static final ReentrantLock LOCK = new ReentrantLock();

  private SevenDaysTelnetConnectionLock() {
  }

  static void lock() {
    LOCK.lock();
  }

  static void unlock() {
    LOCK.unlock();
  }
}
