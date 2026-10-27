package com.yuki.rubinco.guild;

import java.time.LocalDate;
import java.util.List;

/**
 * The beer the visitor pours as the keeper (the "ビール" window). Whoever comes over orders a base and something to add;
 * the order is fixed for that resident and day, so it is the same however often the page is opened. Nothing about the
 * visitor's pouring is sent back or kept.
 */
final class GuildBrew {
  record Choice(String key, String name, String taste) { }

  /** One order: what they ask for, and the line on the ticket. */
  record Order(String base, String extra, String ticket) { }

  static final List<Choice> BASES = List.of(new Choice("lager", "ラガー", "すっきりした一杯"),
      new Choice("ale", "エール", "香りのいい一杯"), new Choice("stout", "スタウト", "黒くて苦い一杯"));
  static final List<Choice> EXTRAS = List.of(new Choice("none", "なし", "何も足さずに"),
      new Choice("honey", "蜂蜜", "蜂蜜をひとさじ"), new Choice("herb", "ハーブ", "ハーブを少し"), new Choice("berry", "木いちご", "木いちごを浮かべて"));

  private GuildBrew() { }

  static Order order(long characterId, LocalDate day) {
    long seed = Math.floorMod(characterId * 7919L + day.toEpochDay() * 104729L, 1_000_003L);
    Choice base = BASES.get((int) (seed % BASES.size()));
    Choice extra = EXTRAS.get((int) (seed / BASES.size() % EXTRAS.size()));
    return new Order(base.key(), extra.key(), base.taste() + "に、" + extra.taste());
  }
}
