package com.yuki.sevendays_states.entity;

public enum ReactionType {
  NICE("👍"), SURPRISE("😮"), LAUGH("😂"), DANGER("⚠️");

  private final String emoji;

  ReactionType(String emoji) {
    this.emoji = emoji;
  }

  public String emoji() {
    return emoji;
  }
}
