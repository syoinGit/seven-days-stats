package com.yuki.sevendays_states.service;

public enum KarenPostTheme {
  EXPLORATION(27, 50, 250),
  DAILY_LIFE(25, 30, 180),
  TRAVEL(18, 80, 350),
  COMBAT(11, 120, 600),
  BLOOD_MOON(3, 200, 1000),
  SELFIE(13, 300, 1500),
  GLAMOUR_SELFIE(3, 1000, 5000);

  private final int weight;
  private final int minimumLikes;
  private final int maximumLikes;

  KarenPostTheme(int weight, int minimumLikes, int maximumLikes) {
    this.weight = weight;
    this.minimumLikes = minimumLikes;
    this.maximumLikes = maximumLikes;
  }

  public int weight() { return weight; }

  public int minimumLikes() { return minimumLikes; }

  public int maximumLikes() { return maximumLikes; }
}
