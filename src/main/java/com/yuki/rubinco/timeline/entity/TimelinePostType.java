package com.yuki.rubinco.timeline.entity;

import com.yuki.rubinco.watchpoint.ai.entity.AiPostType;
import java.util.Optional;

/** A feed category used for selection, copy variation and visual tone. */
public enum TimelinePostType {
  LOGIN(100, 0),
  LOGOUT(100, 0),
  PLAYER_DEATH(100, 0),
  BLOOD_MOON(100, 1440),
  AIR_DROP(82, 2),
  HORDE_ALERT(100, 0),
  WORLD_EVENT(82, 2),
  VEHICLE(45, 8),
  SLEEPER(38, 8),
  KILL(24, 12),
  DIARY(100, 0),
  PLAYER_MESSAGE(100, 0),
  WATCHPOINT(100, 0),
  PLAYER_ANALYSIS(100, 0),
  SERVER_ANALYSIS(100, 0),
  DAILY_SUMMARY(100, 0),
  SURVIVOR_KAREN(100, 0),
  SURVIVOR_MARK(100, 0);

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
      case WATCHPOINT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY,
          DIARY, BLOOD_MOON, AIR_DROP, HORDE_ALERT, WORLD_EVENT, SURVIVOR_KAREN, SURVIVOR_MARK -> false;
      default -> true;
    };
  }

  public Optional<String> systemActorName() {
    return switch (this) {
      case BLOOD_MOON -> Optional.of("BLOOD MOON ALERT");
      case AIR_DROP, WORLD_EVENT -> Optional.of("WORLD INTEL");
      case HORDE_ALERT -> Optional.of("HORDE WATCH");
      // One AI voice: analysis posts are WATCHPOINT too.
      case WATCHPOINT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY -> Optional.of("WATCHPOINT");
      case DIARY -> Optional.of("冒険記録局");
      case SURVIVOR_KAREN -> Optional.of("サバイバーカレン");
      case SURVIVOR_MARK -> Optional.of("サバイバーマーク");
      default -> Optional.empty();
    };
  }

  public Optional<String> avatarPath() {
    return switch (this) {
      case BLOOD_MOON -> Optional.of("/img/blood-moon-alert-avatar.png");
      case HORDE_ALERT -> Optional.of("/img/horde-watch-avatar.png");
      case AIR_DROP -> Optional.of("/img/air-drop-avatar.png");
      case DIARY -> Optional.of("/img/field-journal-avatar.png");
      case WORLD_EVENT -> Optional.of("/img/world-intel-avatar.png");
      case WATCHPOINT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY ->
          Optional.of("/img/watchpoint-avatar.png");
      case SURVIVOR_KAREN -> Optional.of("/img/survivor-karen-avatar.png");
      case SURVIVOR_MARK -> Optional.of("/img/survivor-mark-avatar.png");
      default -> Optional.empty();
    };
  }

  public String tagLabel() {
    return switch (this) {
      case LOGIN -> "ログイン";
      case LOGOUT -> "ログアウト";
      case KILL, PLAYER_DEATH -> "戦闘";
      case BLOOD_MOON -> "血月";
      case AIR_DROP -> "補給";
      case HORDE_ALERT -> "ホード";
      case WORLD_EVENT -> "ワールド";
      case SLEEPER -> "探索";
      case VEHICLE -> "移動";
      case PLAYER_MESSAGE -> "つぶやき";
      case DIARY -> "日記";
      case WATCHPOINT -> "観測";
      case PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY -> "分析";
      case SURVIVOR_KAREN -> "暮らし";
      case SURVIVOR_MARK -> "道中";
    };
  }

  public String tone() {
    return switch (this) {
      case LOGIN -> "login";
      case LOGOUT -> "logout";
      case KILL, PLAYER_DEATH -> "combat";
      case BLOOD_MOON -> "blood-moon";
      case HORDE_ALERT, WORLD_EVENT, SLEEPER -> "warning";
      case AIR_DROP -> "supply";
      case VEHICLE -> "movement";
      case PLAYER_MESSAGE -> "community";
      case DIARY -> "exploration";
      // AI observations are timeline participants, not alerts: use the same community colour as
      // survivor posts so the feed does not visually separate humans and generated commentary.
      case WATCHPOINT, PLAYER_ANALYSIS, SERVER_ANALYSIS, DAILY_SUMMARY -> "community";
      case SURVIVOR_KAREN -> "karen";
      case SURVIVOR_MARK -> "mark";
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
