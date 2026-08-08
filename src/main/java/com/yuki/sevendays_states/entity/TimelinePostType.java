package com.yuki.sevendays_states.entity;

/** A feed category used for selection, copy variation and visual tone. */
public enum TimelinePostType {
  LOGIN(100, 0),
  LOGOUT(100, 0),
  PLAYER_DEATH(100, 0),
  BLOOD_MOON(100, 1440),
  WORLD_EVENT(82, 2),
  VEHICLE(45, 8),
  SLEEPER(38, 8),
  KILL(24, 12),
  PLAYER_MESSAGE(100, 0),
  WATCHPOINT(100, 0);

  private final int publishChance;
  private final int cooldownMinutes;

  TimelinePostType(int publishChance, int cooldownMinutes) {
    this.publishChance = publishChance;
    this.cooldownMinutes = cooldownMinutes;
  }

  public int publishChance() { return publishChance; }

  public int cooldownMinutes() { return cooldownMinutes; }

  public boolean isImmediate() { return publishChance == 100; }
}
