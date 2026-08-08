package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.TimelinePostType;
import java.util.List;
import org.springframework.stereotype.Component;

/** Fact-safe, deterministic copy generation with a deliberately pulpy zombie-movie voice. */
@Component
public class TimelineMessageFactory {
  private static final List<String> LOGIN_OPEN = List.of(
      "無線に生存者の反応！", "荒野の回線が一つ灯った！", "観測網に新しい足音！",
      "今日の出演者が到着！", "死者の街にログイン音！", "通信は生きている！",
      "扉が開いた。人間だ！", "主役が荒野へ戻ってきた！", "生存信号をキャッチ！", "さあ、カメラを回せ！");
  private static final List<String> LOGIN_END = List.of(
      "ゾンビ諸君、勤務開始だ。", "今夜の物語が動き出す。", "弾薬と運を忘れずに。",
      "静かな時間はここまで。", "世界は相変わらず歓迎していない。", "無事に帰るまでが探索だ。",
      "お楽しみはこれからだ！", "ドアを閉めろ。念のため二度だ。", "荒廃世界へ、おかえり。", "WATCHPOINTはポップコーンを用意した。");
  private static final List<String> KILL_VERBS = List.of(
      "討伐", "処刑", "撃破", "始末", "粉砕", "退場させ", "亡き者にし", "永眠させ",
      "墓場へ送り返し", "映画から降板させ", "沈黙させ", "スクラップにし");
  private static final List<String> KILL_SCENES = List.of(
      "荒野で", "探索先で", "瓦礫の向こうで", "死者の群れのただ中で", "帰り道で",
      "文明の残骸を背に", "世界の片隅で", "逃げ場のない戦場で", "今日という舞台で", "ゾンビ映画さながらに");
  private static final List<String> KILL_END = List.of(
      "荒廃世界の平和は守られた！", "お楽しみはこれからだ！", "ジュースをおごってやろう。",
      "感染者側には残念なお知らせだ。", "今日も人類が一本取った！", "エンドロールにはまだ早い。",
      "墓地の予約が一件増えた。", "ゾンビ労働組合から苦情が来そうだ。", "見事だ。次の客を通せ！",
      "荒野の人口統計が少し改善した。", "その調子だ、生存者！", "拍手は弾薬の節約にならないぞ。",
      "この映画、主役はまだ交代しない。", "死者の行進に空席ができた。", "脅威を一つ、請求書なしで片づけた。",
      "今のは予告編に使える。", "平和は短い。だが今は祝おう。", "感染者の勤務時間は終了だ。",
      "記録完了。かなり景気のいい一撃だ。", "次のゾンビは考え直した方がいい。");
  private static final List<String> MEME_STINGERS = List.of(
      "", "", "", "", "", "", "", "",
      "現場からは以上です。", "はい、優勝。", "生存ヨシ！", "これは良い仕事。",
      "フラグは回収されなかった。", "ゾンビ側、解散。", "完全に主人公の動き。", "実質ノーダメージ。",
      "この展開、嫌いじゃない。", "タイムラインがざわつくやつ。", "RTAなら好タイムだ。", "仕様です。",
      "知らんけど、生存率は上がった。", "ワンチャンどころか確定演出。", "今日のMVP候補。", "異論はゾンビのみ認める。",
      "文明、まだいけます。", "その発想はなかった。ゾンビにも。", "よく訓練された生存者だ。", "次回も期待している。",
      "コメント欄があれば拍手で埋まる。", "これは記録に残るやつ。", "予定調和？いいや、実力だ。", "強い。説明は以上。"
  );
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
    return switch (type) {
      case LOGIN -> pick(LOGIN_OPEN, sourceHash, 0) + "\n" + actor + "がログインした。\n" + pick(LOGIN_END, sourceHash, 1);
      case LOGOUT -> actor + "がログアウトした。\n" + pick(List.of(
          "生存信号は一時停止。", "無事な帰還を記録！", "荒野は次の登場を待つ。", "扉は閉じた。たぶん。",
          "今夜の生存者は休憩へ。", "無線は静かになった。", "帰還ルートは確保された。", "エンドロールにはまだ早い。",
          "次回予告までしばし休憩。", "ゾンビに見送られずに済んだ。"), sourceHash, 1);
      case PLAYER_DEATH -> actor + "が力尽きた！\n" + pick(List.of(
          "カット！次のテイクではもっと安全に。", "荒野は容赦なく、リスポーンは慈悲深い。", "この映画に保険会社はいない。",
          "装備回収班に幸運を。", "今日はゾンビ側が一本取った。", "戦いはここで終わらない。",
          "立て、生存者。続編が待っている。", "墓石を注文するにはまだ早い。", "監督、リテイクを要求する！", "次のシーンで取り返そう。"), sourceHash, 1);
      case KILL -> actor + "が" + pick(KILL_SCENES, sourceHash, 3) + safeDetail(detail, "感染者") + "を"
          + pick(KILL_VERBS, sourceHash, 1) + "！！\n" + joinPunchlines(
              pick(KILL_END, sourceHash, 2), pick(MEME_STINGERS, sourceHash, 4));
      case SLEEPER -> actor + "が探索先で" + safeDetail(detail, "眠っていた敵") + "を起こした！\n"
          + pick(SLEEPER_END, sourceHash, 2);
      case VEHICLE -> actor + "が" + safeDetail(detail, "乗り物") + "で荒野を駆け抜けた！\n" + pick(List.of(
          "徒歩より速いが、映画では目立つ。", "エンジン音は勇気と同じくらい響く。", "ガソリンと判断力を大切に。",
          "荒野のドライブに保険はない。", "バックミラーにも注意。", "道路よりゾンビの方が混んでいる。",
          "今日の逃走シーンを記録！", "車輪は生存率を少し上げる。", "目的地まで、できれば無傷で。", "クラクションは夕食のベルになるぞ。"), sourceHash, 2);
      case BLOOD_MOON -> "ブラッドムーン予定が更新された。\n" + pick(List.of(
          "赤い夜は一度で十分だ。今のうちに壁を厚くしよう。", "弾薬、罠、逃げ道。三つとも確認を。",
          "月が赤くなる前に、やれることは多い。", "今夜でないなら幸運だ。準備時間に変えよう。",
          "屋根の上で見る映画ではない。", "次の上映は満員御礼になりそうだ。", "ゾンビ側の祭日が近づいている。",
          "赤い月にアンコールは不要だ。", "備えろ。招待客はドアを使わない。", "カレンダーに赤丸。文字どおりだ。"), sourceHash, 2);
      case WORLD_EVENT -> safeDetail(detail, "世界で何かが起きた！") + "\n" + pick(WORLD_END, sourceHash, 2);
      default -> actor + "の活動を確認。\n荒野の物語は続く。";
    };
  }

  private String safeDetail(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String joinPunchlines(String main, String stinger) {
    return stinger == null || stinger.isBlank() ? main : main + " " + stinger;
  }

  private String pick(List<String> values, String seed, int salt) {
    int mixed = (seed == null ? 0 : seed.hashCode()) ^ (salt * 0x9e3779b9);
    mixed ^= mixed >>> 16;
    return values.get(Math.floorMod(mixed, values.size()));
  }
}
