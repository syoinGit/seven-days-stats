package com.yuki.rubinco.watchpoint.readmodel;

import java.util.Set;

/** Shared visibility rules for high-signal game events. */
public final class TimelineEventPolicy {

  private static final Set<String> ALWAYS_VISIBLE_KINDS = Set.of(
      "JOIN",
      "LEAVE",
      "WANDERING_HORDE",
      "SCOUT_HORDE");

  private TimelineEventPolicy() {
  }

  public static boolean isAlwaysVisible(String kind) {
    return kind != null && ALWAYS_VISIBLE_KINDS.contains(kind);
  }
}
