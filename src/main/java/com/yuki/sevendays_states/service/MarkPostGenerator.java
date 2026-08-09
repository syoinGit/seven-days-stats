package com.yuki.sevendays_states.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import org.springframework.stereotype.Service;

/** Renders a short, cautious trail report from facts already selected from old logs. */
@Service
public class MarkPostGenerator {

  private static final List<String> OPENERS = List.of(
      "%sに寄った。", "%sを覗いてきた。", "%sの辺りを歩いた。", "%sを一回りした。");
  private static final List<String> TRACE_LINES = List.of(
      "最近ここを通った誰かがいるようだ。", "中は最近かなり荒れたように見える。",
      "静かな場所ではなかったらしい。", "足を止める理由は、まだ残っていた。");
  private static final List<String> ENDINGS = List.of(
      "急ぐほどの場所じゃないが、油断はしない。", "物資より先に出口を確認した。",
      "次は明るいうちに来る。", "嫌な感じは、だいたい当たる。");

  public MarkPost generate(LocalDate date, SurvivorMarkCandidateService.Candidate candidate) {
    SplittableRandom random = new SplittableRandom(date.toEpochDay() ^ candidate.key().hashCode() ^ 0x4d41524bL);
    String location = candidate.poi().isBlank() ? "荒野の一角" : candidate.poi();
    StringBuilder body = new StringBuilder(pick(OPENERS, random).formatted(location));
    String special = specialZombie(candidate.zombieTypes());
    if (!special.isBlank()) {
      body.append('\n').append(special).append("の気配が残っていた。");
    } else if (candidate.kills() >= 3) {
      body.append('\n').append("中はかなり荒れていた。");
    } else if (candidate.sleepers() > 0) {
      body.append('\n').append("奥は寝床になっていたらしい。");
    } else {
      body.append('\n').append("しばらく人の出入りがあったように見える。");
    }
    if (random.nextInt(100) < 35) body.append('\n').append(pick(TRACE_LINES, random));
    body.append('\n').append(pick(ENDINGS, random));
    String scene = "%s, rugged adult western male survivor in his late 30s with short dark hair, light beard, "
        + "weathered jacket, tactical backpack and gloves, documenting an abandoned exploration site"
        .formatted(candidate.poi().isBlank() ? "an abandoned wilderness location" : candidate.poi());
    return new MarkPost(body.toString(), scene, random.nextLong(858_993_460L), subtype(candidate));
  }

  private String specialZombie(List<String> types) {
    return types.stream().map(this::displayZombie).filter(value -> !value.isBlank()).findFirst().orElse("");
  }

  private String displayZombie(String raw) {
    String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    if (value.contains("screamer")) return "スクリーマー";
    if (value.contains("nurse") && value.contains("radiated")) return "放射能まみれのナース";
    if (value.contains("radiated")) return "放射能まみれのゾンビ";
    if (value.contains("demolisher")) return "デモリッシャー";
    if (value.contains("cop")) return "ゾンビ警官";
    if (value.contains("feral")) return "凶暴なゾンビ";
    if (value.contains("wight")) return "ワイト";
    if (value.contains("soldier")) return "兵士ゾンビ";
    return "";
  }

  private String subtype(SurvivorMarkCandidateService.Candidate candidate) {
    if (specialZombie(candidate.zombieTypes()).isBlank() && candidate.sleepers() == 0) return "TRAIL";
    return candidate.kills() >= 3 || !specialZombie(candidate.zombieTypes()).isBlank()
        ? "HAZARD" : "POI";
  }

  private String pick(List<String> values, SplittableRandom random) {
    return values.get(random.nextInt(values.size()));
  }

  public record MarkPost(String body, String imageScene, long imageSeed, String subtype) { }
}
