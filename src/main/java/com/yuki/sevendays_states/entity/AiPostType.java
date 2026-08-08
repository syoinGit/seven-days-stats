package com.yuki.sevendays_states.entity;

/** AI timeline post types. Only NORMAL may be broadcast into the game. */
public enum AiPostType {
  NORMAL(true, "観測"),
  PLAYER_ANALYSIS(false, "生存者分析"),
  SERVER_ANALYSIS(false, "サーバー分析"),
  DAILY_SUMMARY(false, "日次まとめ");

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
