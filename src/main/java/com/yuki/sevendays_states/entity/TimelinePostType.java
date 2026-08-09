package com.yuki.sevendays_states.entity;

import java.util.Optional;

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
  DIARY(100, 0),
  PLAYER_MESSAGE(100, 0),
  WATCHPOINT(100, 0),
  PLAYER_ANALYSIS(100, 0),
  SERVER_ANALYSIS(100, 0),
  DAILY_SUMMARY(100, 0);

  private final int publishChance;
  private final int cooldownMinutes;

  TimelinePostType(int publishChance, int cooldownMinutes) {
    this.publishChance = publishChance;
    this.cooldownMinutes = cooldownMinutes;
  }

  public int publishChance() { return publishChance; }

  public int cooldownMinutes() { return cooldownMinutes; }

  public boolean isImmediate() { return publishChance == 100; }

  public boolean isAiGenerated() {
    return switch (this) {
      case WATCHPOINT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY -> true;
      default -> false;
    };
  }

  public boolean linksActorToPlayer() {
    return switch (this) {
      case LOGIN, LOGOUT, WATCHPOINT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY,
          DIARY, BLOOD_MOON, WORLD_EVENT -> false;
      default -> true;
    };
  }

  public Optional<String> systemActorName() {
    return switch (this) {
      case LOGIN, LOGOUT -> Optional.of("CONNECTION MONITOR");
      case BLOOD_MOON -> Optional.of("BLOOD MOON ALERT");
      case WORLD_EVENT -> Optional.of("WORLD INTEL");
      case WATCHPOINT -> Optional.of("WATCHPOINT");
      case PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY -> Optional.of("観測分析局");
      case DIARY -> Optional.of("冒険記録局");
      default -> Optional.empty();
    };
  }

  public Optional<String> avatarPath() {
    return switch (this) {
      case LOGIN, LOGOUT -> Optional.of("/img/connection-monitor-avatar.png");
      case BLOOD_MOON -> Optional.of("/img/blood-moon-alert-avatar.png");
      case WORLD_EVENT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY, DIARY ->
          Optional.of("/img/world-intel-avatar.png");
      case WATCHPOINT -> Optional.of("/img/watchpoint-avatar.png");
      default -> Optional.empty();
    };
  }

  public String tagLabel() {
    return switch (this) {
      case LOGIN -> "ONLINE";
      case LOGOUT -> "OFFLINE";
      case KILL, PLAYER_DEATH -> "ELIMINATION";
      case BLOOD_MOON -> "BLOOD MOON ALERT";
      case WORLD_EVENT -> "WORLD INTEL";
      case SLEEPER -> "EXPLORATION";
      case VEHICLE -> "TRAVEL";
      case PLAYER_MESSAGE -> "SURVIVOR POST";
      case DIARY -> "FIELD JOURNAL";
      case WATCHPOINT -> "AI OBSERVATION";
      case PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY -> "ANALYSIS";
    };
  }

  public String tone() {
    return switch (this) {
      case LOGIN -> "login";
      case LOGOUT -> "logout";
      case KILL, PLAYER_DEATH -> "combat";
      case BLOOD_MOON -> "blood-moon";
      case WORLD_EVENT, SLEEPER -> "warning";
      case VEHICLE -> "movement";
      case PLAYER_MESSAGE -> "community";
      case DIARY -> "exploration";
      case WATCHPOINT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY -> "ai";
    };
  }

  public static TimelinePostType fromAiPostType(AiPostType type) {
    return switch (type) {
      case NORMAL -> WATCHPOINT;
      case PLAYER_ANALYSIS -> PLAYER_ANALYSIS;
      case SERVER_ANALYSIS -> SERVER_ANALYSIS;
      case DAILY_SUMMARY -> DAILY_SUMMARY;
    };
  }

  public static Optional<TimelinePostType> parse(String value) {
    try {
      return Optional.of(valueOf(value == null ? "" : value));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }
}
