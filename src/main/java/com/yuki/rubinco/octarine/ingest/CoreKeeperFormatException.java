package com.yuki.rubinco.octarine.ingest;

/** The save does not match the verified layout. Callers must keep the previous snapshot. */
public class CoreKeeperFormatException extends RuntimeException {
  public CoreKeeperFormatException(String message) {
    super(message);
  }
}
