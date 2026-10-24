package com.yuki.rubinco.worldprogress.service;

/** UNKNOWN covers unsupported items and is never shown or evaluated as "not achieved". */
public enum ProgressState {
  ACHIEVED("達成"),
  NOT_ACHIEVED("未達"),
  UNKNOWN("不明");

  private final String label;

  ProgressState(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static ProgressState from(String value) {
    if (value == null) return UNKNOWN;
    try {
      return valueOf(value);
    } catch (IllegalArgumentException exception) {
      return UNKNOWN;
    }
  }
}
