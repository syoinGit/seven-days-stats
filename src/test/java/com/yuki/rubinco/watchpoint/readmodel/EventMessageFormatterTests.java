package com.yuki.rubinco.watchpoint.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventMessageFormatterTests {

  private final EventMessageFormatter formatter = new EventMessageFormatter();

  @Test
  void killMessagesKeepKillerAndVictimWhileUsingVariations() {
    Set<String> messages = new HashSet<>();

    for (int i = 0; i < 20; i++) {
      String message = formatter.format("KILL", "DDD烈火王テムジン", "討伐した", "ゾンビ" + i, "");

      assertThat(message).contains("DDD烈火王テムジン");
      assertThat(message).contains("ゾンビ" + i);
      assertThat(message).contains("！\n");
      messages.add(message.replace("ゾンビ" + i, "ゾンビ"));
    }

    assertThat(messages).hasSizeGreaterThanOrEqualTo(8);
  }

  @Test
  void movementMessageContainsDestinationAndDistance() {
    assertThat(formatter.format("VEHICLE_MOVE", "PlayerA", "移動した", "オートバイ|42.5", "軍事基地"))
        .isEqualTo("PlayerAが軍事基地でオートバイに乗って42.5 m移動！");
  }

  @Test
  void killAndSleeperMessagesIncludePoiWhenKnown() {
    assertThat(formatter.format("KILL", "PlayerA", "討伐した", "看護師", "病院"))
        .startsWith("PlayerAが病院で看護師");
    assertThat(formatter.format("SLEEPER_SPAWN", "PlayerA", "起こした", "看護師", "病院"))
        .startsWith("病院で、PlayerA");
  }

  @Test
  void sleeperSpawnMessagesKeepPlayerAndEnemyWhileUsingVariations() {
    Set<String> messages = new HashSet<>();

    for (int i = 0; i < 20; i++) {
      String message = formatter.format("SLEEPER_SPAWN", "魅惑のこし餡ぼでぃ", "眠っていた敵を起こした", "木こり" + i, "");

      assertThat(message).contains("魅惑のこし餡ぼでぃ");
      assertThat(message).contains("木こり" + i);
      messages.add(message.replace("木こり" + i, "木こり"));
    }

    assertThat(messages).hasSizeGreaterThanOrEqualTo(8);
  }
}
