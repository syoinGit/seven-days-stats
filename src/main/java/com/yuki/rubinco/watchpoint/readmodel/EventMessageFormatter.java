package com.yuki.rubinco.watchpoint.readmodel;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EventMessageFormatter {

  private static final List<String> KILL_PATTERNS = List.of(
      "%sが%sを討伐した！ 荒野の治安が一瞬だけ回復。",
      "%sが%sを倒した！ 本日のサバイバル業務、進捗あり。",
      "%sが%sを片付けた！ 終末世界にも清掃の心。",
      "%sが%sを沈黙させた！ ゾンビ側の議事録に深刻な空白。",
      "%sが%sを撃退した！ 物資より先に武勇伝が増えた。",
      "%sが%sを成敗した！ この辺りの空気が少しだけマシになった。",
      "%sが%sを討ち取った！ 荒野の監査ログに赤丸案件。",
      "%sが%sを仕留めた！ 今日の晩飯が安全になるとは言っていない。",
      "%sが%sを退場させた！ 入場券はたぶん持っていなかった。",
      "%sが%sを討伐した！ 拠点の壁が小さく拍手している。"
  );

  private static final List<String> ENCOUNTER_PATTERNS = List.of(
      "%sが%sと遭遇した。荒野の接客品質は相変わらず最低。",
      "%sが%sを起こした。寝起きの機嫌はもちろん最悪。",
      "%sの近くで%sが活動開始。終末の目覚まし時計が鳴った。",
      "%sが%sに見つかった。かくれんぼ部門、敗北寄り。",
      "%sが%sの気配を踏んだ。床板より先に運が鳴った。",
      "%sの周辺で%sがざわついた。静かな探索、ここで終了。",
      "%sが%sの営業開始に巻き込まれた。営業時間は命が尽きるまで。",
      "%sが%sを発見した。向こうもこちらを発見したのが問題。",
      "%sの探索先に%sが出勤した。無給で、全力で、しつこい。",
      "%sが%sと鉢合わせた。荒野の予定表には書いてなかった。"
  );

  public String format(String kind, String actor, String actionText, String detailText, String poiName) {
    String safeActor = actor == null || actor.isBlank() ? "誰か" : actor;
    if ("DAY_START".equals(kind)) {
      String day = detailText == null || detailText.isBlank() ? "新しい一日" : detailText;
      return day + "が始まった。荒野の冒険記録を更新する。";
    }
    if ("PLAYER_DEATH".equals(kind)) {
      if (detailText == null || detailText.isBlank() || safeActor.equals(detailText)) {
        return safeActor + "が荒野で力尽きた。装備回収までが冒険です。";
      }
      return safeActor + "が" + detailText + "に倒された。荒野は容赦がない。";
    }
    if ("KILL".equals(kind)) {
      if (detailText == null || detailText.isBlank()) {
        return safeActor + "が何かを討伐した！ 記録係は肝心なところで目をそらした。";
      }
      String message = pattern(KILL_PATTERNS, kind, safeActor, detailText, poiName)
          .formatted(safeActor, detailText)
          .replaceFirst("！ ", "！\n");
      if (poiName != null && !poiName.isBlank()) {
        message = message.replaceFirst(
            java.util.regex.Pattern.quote(safeActor + "が"),
            java.util.regex.Matcher.quoteReplacement(safeActor + "が" + poiName + "で"));
      }
      return message;
    }
    if ("JOIN".equals(kind)) {
      if (poiName == null || poiName.isBlank()) {
        return safeActor + "がログインした";
      }
      return safeActor + "が" + poiName + "でログインした";
    }
    if ("LEAVE".equals(kind)) {
      return safeActor + "がログアウトした";
    }
    if ("VEHICLE_MOVE".equals(kind)) {
      String destination = poiName == null || poiName.isBlank() ? "荒野" : poiName;
      String[] movement = detailText == null ? new String[0] : detailText.split("\\|", 2);
      String vehicle = movement.length > 0 && !movement[0].isBlank() ? movement[0] : "乗り物";
      String distance = movement.length > 1 && !movement[1].isBlank() ? movement[1] + " m" : "";
      return safeActor + "が" + destination + "で" + vehicle + "に乗って" + distance + "移動！";
    }
    if ("SLEEPER_RESTORE".equals(kind)) {
      return "眠っていた敵が再配置された";
    }
    if ("SLEEPER_SPAWN".equals(kind) && detailText != null && !detailText.isBlank()) {
      String message = pattern(ENCOUNTER_PATTERNS, kind, safeActor, detailText, poiName)
          .formatted(safeActor, detailText);
      return poiName == null || poiName.isBlank() ? message : poiName + "で、" + message;
    }
    if ("AIR_DROP".equals(kind)) {
      return poiName == null || poiName.isBlank()
          ? "補給物資が投下された"
          : poiName + "付近に補給物資が投下された";
    }
    if ("WANDERING_HORDE".equals(kind)) {
      return safeActor + "の近くで徘徊ホードが発生した";
    }
    if ("SCOUT_HORDE".equals(kind)) {
      return poiName == null || poiName.isBlank()
          ? "スクリーマーの気配がした"
          : poiName + "付近でスクリーマーの気配がした";
    }
    if ("SCREAMER_SPAWN".equals(kind)) {
      return poiName == null || poiName.isBlank()
          ? "スクリーマーが出現した"
          : poiName + "付近にスクリーマーが出現した";
    }
    if ("BLOOD_MOON".equals(kind)) {
      return detailText == null || detailText.isBlank()
          ? "ブラッドムーン予定が更新された"
          : "ブラッドムーン予定が更新された（" + detailText + "）";
    }
    if ("VEHICLE_REMOVED".equals(kind)) {
      if (detailText == null || detailText.isBlank()) {
        return safeActor + "の乗り物が消失した";
      }
      return safeActor + "の" + detailText + "が消失した";
    }
    if ("VEHICLE_LOADED".equals(kind) || "VEHICLE_POST_INIT".equals(kind)) {
      if (detailText == null || detailText.isBlank()) {
        return safeActor + "の乗り物を確認した";
      }
      return safeActor + "の" + detailText + "を確認した";
    }
    if (poiName == null || poiName.isBlank()) {
      return safeActor + "が" + actionText;
    }
    return safeActor + "が" + poiName + "で" + actionText;
  }

  private String pattern(List<String> patterns, String kind, String actor, String detailText, String poiName) {
    String seed = String.join("|",
        kind == null ? "" : kind,
        actor == null ? "" : actor,
        detailText == null ? "" : detailText,
        poiName == null ? "" : poiName);
    return patterns.get(Math.floorMod(seed.hashCode(), patterns.size()));
  }
}
