package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuki.sevendays_states.entity.TimelinePostType;
import com.yuki.sevendays_states.entity.AiPostType;
import java.util.HashSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TimelineMessageFactoryTests {
  private final TimelineMessageFactory factory = new TimelineMessageFactory();

  @Test
  void killMessagesAreTwoLineFactSafeZombieMovieCopyWithOverOneThousandVariants() {
    var messages = IntStream.range(0, 20000)
        .mapToObj(index -> factory.message(
            TimelinePostType.KILL, "後輩", "zombieBusinessMan", "kill-source-" + index))
        .toList();

    assertThat(new HashSet<>(messages)).hasSizeGreaterThanOrEqualTo(1000);
    assertThat(messages).allSatisfy(message -> {
      assertThat(message).contains("後輩", "zombieBusinessMan", "\n");
      assertThat(message.lines()).hasSize(2);
    });
  }

  @Test
  void internetFlavourAppearsSometimesButNotInEveryPost() {
    var messages = IntStream.range(0, 500)
        .mapToObj(index -> factory.message(
            TimelinePostType.KILL, "後輩", "感染者", "meme-source-" + index))
        .toList();

    assertThat(messages).anyMatch(message -> message.contains("現場からは以上です。")
        || message.contains("優勝") || message.contains("RTA") || message.contains("仕様です。"));
    assertThat(messages).anyMatch(message -> !message.contains("現場からは以上です。")
        && !message.contains("優勝") && !message.contains("RTA") && !message.contains("仕様です。")
        && !message.contains("ヨシ！") && !message.contains("フラグ"));
  }

  @Test
  void bloodMoonMessagesAreClearlySeparatedIntoTwoLines() {
    assertThat(factory.message(TimelinePostType.BLOOD_MOON, null, null, "blood-moon"))
        .startsWith("ブラッドムーン予定が更新された。\n")
        .hasLineCount(2);
  }

  @Test
  void timelineTypeOwnsItsPresentationAndAiMapping() {
    assertThat(TimelinePostType.BLOOD_MOON.tagLabel()).isEqualTo("BLOOD MOON ALERT");
    assertThat(TimelinePostType.BLOOD_MOON.tone()).isEqualTo("blood-moon");
    assertThat(TimelinePostType.BLOOD_MOON.linksActorToPlayer()).isFalse();
    assertThat(TimelinePostType.fromAiPostType(AiPostType.PLAYER_ANALYSIS))
        .isEqualTo(TimelinePostType.PLAYER_ANALYSIS);
    assertThat(TimelinePostType.PLAYER_ANALYSIS.isAiGenerated()).isTrue();
    assertThat(TimelinePostType.parse("UNKNOWN")).isEmpty();
    assertThat(TimelinePostType.LOGIN.systemActorName()).isEmpty();
    assertThat(TimelinePostType.LOGIN.avatarPath()).isEmpty();
    assertThat(TimelinePostType.BLOOD_MOON.avatarPath()).contains("/img/blood-moon-alert-avatar.png");
    assertThat(TimelinePostType.SERVER_ANALYSIS.avatarPath()).contains("/img/world-intel-avatar.png");
    assertThat(TimelinePostType.AIR_DROP.systemActorName()).contains("WORLD INTEL");
    assertThat(TimelinePostType.HORDE_ALERT.systemActorName()).contains("HORDE WATCH");
    assertThat(TimelinePostType.HORDE_ALERT.avatarPath()).contains("/img/horde-watch-avatar.png");
    assertThat(TimelinePostType.AIR_DROP.avatarPath()).contains("/img/air-drop-avatar.png");
    assertThat(TimelinePostType.AIR_DROP.tone()).isEqualTo("supply");
    assertThat(TimelinePostType.DIARY.avatarPath()).contains("/img/field-journal-avatar.png");
  }

  @Test
  void airDropAndHordeUseIndependentCopy() {
    assertThat(factory.message(TimelinePostType.AIR_DROP, null,
        "補給物資が投下された。", "air-drop")).contains("補給物資").doesNotContain("ホード");
    assertThat(factory.message(TimelinePostType.HORDE_ALERT, null,
        "生存者Aの近くで徘徊ホードが発生した！", "horde"))
        .contains("生存者A", "徘徊ホード").doesNotContain("補給物資");
  }
}
