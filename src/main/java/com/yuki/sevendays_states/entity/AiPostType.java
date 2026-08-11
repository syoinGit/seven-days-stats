package com.yuki.sevendays_states.entity;

/** AI timeline post types. WATCHPOINT's generated statistics are also announced in-game. */
public enum AiPostType {
  NORMAL(true, "観測"),
  PLAYER_ANALYSIS(true, "生存者分析"),
  SERVER_ANALYSIS(true, "サーバー分析"),
  DAILY_SUMMARY(true, "日次まとめ");

  private final boolean gameBroadcastEnabled;
  private final String displayLabel;

  AiPostType(boolean gameBroadcastEnabled, String displayLabel) {
    this.gameBroadcastEnabled = gameBroadcastEnabled;
    this.displayLabel = displayLabel;
  }

  public boolean isGameBroadcastEnabled() {
    return gameBroadcastEnabled;
  }

  public String displayLabel() {
    return displayLabel;
  }
}
