package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.TimelinePostType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Fact-safe, deterministic copy generation with a zombie and horror cinema parody voice. */
@Component
@RequiredArgsConstructor
public class TimelineMessageFactory {
  private static final List<String> LOGIN_OPEN = List.of(
      "無線に生存者の反応！", "荒野の回線が一つ灯った！", "観測網に新しい足音！",
      "今日の出演者が到着！", "死者の街にログイン音！", "通信は生きている！",
      "扉が開いた。人間だ！", "主役が荒野へ戻ってきた！", "生存信号をキャッチ！", "さあ、カメラを回せ！");
  private static final List<String> LOGIN_END = List.of(
      "ゾンビ諸君、勤務開始だ。", "今夜の物語が動き出す。", "弾薬と運を忘れずに。",
      "静かな時間はここまで。", "世界は相変わらず歓迎していない。", "無事に帰るまでが探索だ。",
      "お楽しみはこれからだ！", "ドアを閉めろ。念のため二度だ。", "荒廃世界へ、おかえり。", "WATCHPOINTはポップコーンを用意した。");
  private final TimelineCopyCatalog copyCatalog;
  private static final List<String> SLEEPER_END = List.of(
      "静かな探索は、ここで終了！", "不動産の内見には向かない。", "歓迎はだいたい噛みつきから始まる。",
      "眠りは浅かったらしい。", "隠れ家にも夜勤がいる。", "扉の向こうは平和ではなかった。",
      "映画なら、ここで音楽が止まる。", "次の部屋は慎重に。", "寝起きの機嫌は最悪だ。", "探索の税金を納める時間だ。");
  private static final List<String> WORLD_END = List.of(
      "予定変更の時間だ！", "荒野は今日も脚本を読んでいない。", "安全な日など存在しないらしい。",
      "カメラを回せ！", "備えがあれば、ほんの少し安心だ。", "遠くで嫌な予感が育っている。",
      "無線の向こうで何かが笑った。", "避難計画を再確認しよう。", "静寂は長続きしない。", "嫌なBGMが聞こえてきそうだ。");

  public String message(TimelinePostType type, String playerName, String detail, String sourceHash) {
    String actor = playerName == null || playerName.isBlank() ? "誰か" : playerName;
    String copy = switch (type) {
      case LOGIN -> pick(LOGIN_OPEN, sourceHash, 0) + "\n" + actor + "がログインした。\n" + pick(LOGIN_END, sourceHash, 1);
      case LOGOUT -> actor + "がログアウトした。\n" + pick(List.of(
          "生存信号は一時停止。", "無事な帰還を記録！", "荒野は次の登場を待つ。", "扉は閉じた。たぶん。",
          "今夜の生存者は休憩へ。", "無線は静かになった。", "帰還ルートは確保された。", "エンドロールにはまだ早い。",
          "次回予告までしばし休憩。", "ゾンビに見送られずに済んだ。"), sourceHash, 1);
      case PLAYER_DEATH -> actor + "が力尽きた！\n" + pick(List.of(
          "カット！次のテイクではもっと安全に。", "荒野は容赦なく、リスポーンは慈悲深い。", "この映画に保険会社はいない。",
          "装備回収班に幸運を。", "今日はゾンビ側が一本取った。", "戦いはここで終わらない。",
          "立て、生存者。続編が待っている。", "墓石を注文するにはまだ早い。", "監督、リテイクを要求する！", "次のシーンで取り返そう。"), sourceHash, 1);
      case KILL -> actor + "が" + pick("kill-scenes", sourceHash, 3) + safeDetail(detail, "感染者") + "を"
          + pick("kill-verbs", sourceHash, 1) + "！！\n" + joinPunchlines(
              pick("kill-endings", sourceHash, 2), pick("genre-stingers", sourceHash, 4),
              pick("kill-camera-beats", sourceHash, 5), pick("kill-sound-beats", sourceHash, 6));
      case SLEEPER -> actor + "が探索先で" + safeDetail(detail, "眠っていた敵") + "を起こした！\n"
          + pick(SLEEPER_END, sourceHash, 2);
      case VEHICLE -> actor + "が" + safeDetail(detail, "乗り物") + "で荒野を駆け抜けた！\n" + pick(List.of(
          "徒歩より速いが、映画では目立つ。", "エンジン音は勇気と同じくらい響く。", "ガソリンと判断力を大切に。",
          "荒野のドライブに保険はない。", "バックミラーにも注意。", "道路よりゾンビの方が混んでいる。",
          "今日の逃走シーンを記録！", "車輪は生存率を少し上げる。", "目的地まで、できれば無傷で。", "クラクションは夕食のベルになるぞ。"), sourceHash, 2);
      case AIR_DROP -> safeDetail(detail, "補給物資が投下された！") + "\n" + pick(List.of(
          "空から届いた請求書なしの贈り物だ。", "早い者勝ちだ。ゾンビに先を越されるな。",
          "パラシュートを追え。中身は開けてからのお楽しみ。", "荒野にも配達サービスは残っていたらしい。",
          "回収地点までの道中は、安全保証の対象外だ。"), sourceHash, 2);
      case HORDE_ALERT -> safeDetail(detail, "徘徊ホードを観測した！") + "\n" + pick(List.of(
          "団体客だ。歓迎の準備はできているか。", "静かな散歩とはいかなさそうだ。",
          "進路に注意。あちらは予約なしで押しかける。", "荒野の交通量が急に増えた。",
          "ドアと弾倉を確認しよう。順番はどちらでもいい。"), sourceHash, 2);
      case WORLD_EVENT -> safeDetail(detail, "世界で何かが起きた！") + "\n" + pick(WORLD_END, sourceHash, 2);
      default -> actor + "の活動を確認。\n荒野の物語は続く。";
    };
    return type == TimelinePostType.KILL
        ? copy
        : copy + " " + pick("timeline-beats", sourceHash, 7);
  }

  private String safeDetail(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  long killVariantCapacity() {
    return (long) copyCatalog.part("kill-scenes").size()
        * copyCatalog.part("kill-verbs").size()
        * copyCatalog.part("kill-endings").size()
        * copyCatalog.part("genre-stingers").size()
        * copyCatalog.part("kill-camera-beats").size()
        * copyCatalog.part("kill-sound-beats").size();
  }

  private String joinPunchlines(String... phrases) {
    return java.util.Arrays.stream(phrases)
        .filter(phrase -> phrase != null && !phrase.isBlank())
        .collect(java.util.stream.Collectors.joining(" "));
  }

  private String pick(List<String> values, String seed, int salt) {
    int mixed = (seed == null ? 0 : seed.hashCode()) ^ (salt * 0x9e3779b9);
    mixed ^= mixed >>> 16;
    return values.get(Math.floorMod(mixed, values.size()));
  }

  private String pick(String partId, String seed, int salt) {
    return pick(copyCatalog.part(partId), seed, salt);
  }
}
