package com.yuki.rubinco.luminite.ingest;

/** The save is truncated, a different format, or fails a structural check. */
public class TerrariaFormatException extends RuntimeException {
  public TerrariaFormatException(String message) {
    super(message);
  }
}
