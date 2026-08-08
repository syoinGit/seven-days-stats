package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.TimelinePostType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Composable copy banks give every event type hundreds of deterministic variants without an AI
 * request. Each phrase only describes facts contained in the source log.
 */
@Component
public class TimelineMessageFactory {
  private static final List<String> LOGIN_OPEN = List.of(
      "監視端末がノイズを拾った。", "無線に生存者の反応。", "荒野の回線が一つ灯った。",
      "ドアのきしむ音より確かな知らせ。", "観測網に新しい足音。", "コーヒーを温める間もなく、",
      "今日の出演者が到着。", "死者の街にログイン音。", "通信は生きている。", "WATCHPOINT、起床。");
  private static final List<String> LOGIN_END = List.of(
      "生存信号を確認。", "荒野へ復帰した。", "今夜の物語が動き出す。", "ゾンビ諸君、勤務開始です。",
      "弾薬と運を忘れずに。", "静かな時間はここまで。", "世界は相変わらず歓迎していない。",
      "監視を再開する。", "無事に帰るまでが探索。", "カメラは見ている。");
  private static final List<String> KILL_OPEN = List.of(
      "脅威を一つ減らした。", "感染者の勤務時間が終了。", "荒野の人口統計が少し改善。",
      "また一体、映画のエキストラが退場。", "静寂を買うための一発。", "死者の行進に空席ができた。",
      "感染者側には残念なお知らせ。", "今日も反撃の記録。", "戦闘の痕跡を確認。", "生存の帳尻を合わせた。");
  private static final List<String> SLEEPER_END = List.of(
      "静かな探索は、ここで終了。", "不動産の内見には向かない。", "歓迎はだいたい噛みつきから始まる。",
      "眠りは浅かったらしい。", "隠れ家にも夜勤がいる。", "扉の向こうは平和ではなかった。",
      "映画なら、ここで音楽が止まる。", "次の部屋は慎重に。", "寝起きの機嫌は最悪だ。", "探索の税金を納めた。");
  private static final List<String> WORLD_END = List.of(
      "WATCHPOINTは記録を継続する。", "生存者諸君、予定変更の時間です。", "荒野は今日も脚本を読んでいない。",
      "安全な日など、たぶん存在しない。", "カメラを回せ。", "備えがあれば少しだけ安心。",
      "遠くで嫌な予感が育っている。", "無線の向こうで何かが笑った。", "避難計画を再確認。", "静寂は長続きしない。");

  public String message(TimelinePostType type, String playerName, String detail, String sourceHash) {
    String actor = playerName == null || playerName.isBlank() ? "誰か" : playerName;
    return switch (type) {
      case LOGIN -> pick(LOGIN_OPEN, sourceHash, 0) + " " + actor + "がログインした。 " + pick(LOGIN_END, sourceHash, 1);
      case LOGOUT -> actor + "がログアウトした。 " + pick(List.of("生存信号は一時停止。", "無事な帰還を記録。", "荒野は次の登場を待つ。", "扉は閉じた。たぶん。", "監視席を一つ空ける。", "今夜の生存者は休憩へ。", "無線は静かになった。", "次のログインまで記録を保留。", "帰還ルートは確保された。", "エンドロールにはまだ早い。"), sourceHash, 1);
      case PLAYER_DEATH -> actor + "が力尽きた。 " + pick(List.of("カット。次のテイクではもっと安全に。", "荒野は容赦なく、リスポーンは慈悲深い。", "この映画に保険会社はいない。", "監視記録に一つ、重いページ。", "次の起床地点で反撃を。", "死者の街は手加減を知らない。", "装備回収班に幸運を。", "今日はゾンビ側が一本取った。", "戦いはここで終わらない。", "生存者の物語は続く。"), sourceHash, 1);
      case KILL -> actor + "が" + safeDetail(detail, "感染者") + "を討伐した。 " + pick(KILL_OPEN, sourceHash, 1);
      case SLEEPER -> actor + "が" + safeDetail(detail, "眠っていた敵") + "を起こした。 " + pick(SLEEPER_END, sourceHash, 1);
      case VEHICLE -> actor + "が" + safeDetail(detail, "乗り物") + "で移動した。 " + pick(List.of("徒歩より速いが、映画では目立つ。", "エンジン音は勇気と同じくらい響く。", "ガソリンと判断力を大切に。", "荒野のドライブには保険がない。", "目的地まで、できれば無傷で。", "バックミラーにも注意。", "道路よりゾンビの方が混んでいる。", "乗り物は良い。壁はもっと良い。", "今日の逃走シーンを記録。", "車輪は生存率を少し上げる。"), sourceHash, 1);
      case WORLD_EVENT -> safeDetail(detail, "世界で何かが起きた。") + " " + pick(WORLD_END, sourceHash, 1);
      default -> actor + "の活動を記録した。";
    };
  }

  private String safeDetail(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

  private String pick(List<String> values, String seed, int salt) {
    return values.get(Math.floorMod((seed == null ? 0 : seed.hashCode()) + salt * 31, values.size()));
  }
}
