package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.TimelinePostType;
import java.util.List;
import org.springframework.stereotype.Component;

/** Fact-safe, deterministic copy generation with a zombie and horror cinema parody voice. */
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
      "討伐した", "処刑した", "撃破した", "始末した", "粉砕した", "退場させた", "亡き者にした", "永眠させた",
      "墓場へ送り返した", "映画から降板させた", "沈黙させた", "スクラップにした");
  private static final List<String> KILL_SCENES = List.of(
      "荒野で", "探索先で", "瓦礫の向こうで", "死者の群れのただ中で", "帰り道で",
      "文明の残骸を背に", "世界の片隅で", "逃げ場のない戦場で", "今日という舞台で", "ゾンビ映画さながらに",
      "モール跡の吹き抜けで", "非常灯の下で", "研究棟らしき廃墟で", "停電したロビーで",
      "廃病院の外階段で", "ガソリンスタンドの陰で");
  private static final List<String> KILL_END = List.of(
      "荒廃世界の平和は守られた！", "お楽しみはこれからだ！", "ジュースをおごってやろう。",
      "感染者側には残念なお知らせだ。", "今日も人類が一本取った！", "エンドロールにはまだ早い。",
      "墓地の予約が一件増えた。", "ゾンビ労働組合から苦情が来そうだ。", "見事だ。次の客を通せ！",
      "荒野の人口統計が少し改善した。", "その調子だ、生存者！", "拍手は弾薬の節約にならないぞ。",
      "この映画、主役はまだ交代しない。", "死者の行進に空席ができた。", "脅威を一つ、請求書なしで片づけた。",
      "今のは予告編に使える。", "平和は短い。だが今は祝おう。", "感染者の勤務時間は終了だ。",
      "記録完了。かなり景気のいい一撃だ。", "次のゾンビは考え直した方がいい。",
      "生存者チームの予算は、今日も減らなかった。", "救急箱を開けずに済むなら、それが一番だ。",
      "監督なら、ここで一度だけ頷く。", "静寂の予算が、また少し削られた。");
  private static final List<String> GENRE_STINGERS = List.of(
      "", "", "", "", "", "", "", "",
      "エンドロールには、まだ早い。", "感染拡大の予算が少し削れた。",
      "ショッピングモールなら、非常口を先に探せ。", "即席武器が役に立つ日ほど、商品棚は遠い。",
      "研究施設の赤い警報灯は、だいたい歓迎の印じゃない。", "培養槽のある部屋には、なるべく入らない。",
      "地下室には、映画でも現実でも先に入らない。", "『大丈夫』と言った直後ほど、BGMを疑え。",
      "カメラが引いたら、背後を確認しよう。", "生還者のクレジットは、まだ流れていない。",
      "閉じた扉の向こうは、だいたい静かではない。", "チェーンソーの音は、安心材料にならない。",
      "非常用エレベーターは、非常時ほど信用できない。", "次のシーンへ進む前に、弾倉を確認。",
      "研究員の置き手紙は、だいたい読まなくていい。", "この街に安全な近道はない。"
  );
  private static final List<String> KILL_CAMERA_BEATS = List.of(
      "次のカットまで、呼吸を整えよう。", "カメラはまだ回っている。", "照明が落ちる前に移動だ。",
      "主人公補正にも、弾薬は必要だ。", "扉の鍵を確認してから次へ進もう。", "効果音が消えた今が移動の好機。",
      "ロビーの時計は、見ない方がいい。", "無線が静かなうちに補給を。", "足跡が増える前に退場しよう。",
      "脇役の油断ほど、上映時間を延ばす。", "壁際の影は、二度見しておこう。", "非常口の看板だけは信じてもいい。",
      "開いたロッカーには、期待より警戒を。", "次の扉は、ゆっくり開けよう。", "映画の地図にも、安全地帯は少ない。",
      "荷物欄に余白があるなら、今のうちだ。", "背後の静けさは、確認して初めて静けさになる。",
      "夜明けまでの尺は、まだ残っている。", "照準を下ろすのは、周囲を見てから。",
      "この場面の教訓は、予備マガジンだ。", "カットが変わる前に、仲間の位置を確認。",
      "次の悲鳴が聞こえる前に、足を動かそう。", "映画なら、この後に静かな部屋が来る。"
  );
  private static final List<String> TIMELINE_BEATS = List.of(
      "無線は、今だけ静かだ。", "非常灯が消える前に、次の手を。", "廃墟は次の場面を待っている。",
      "生還ルートは、常に一つ多く確保しよう。", "カメラの外にも、気配はいる。", "扉を閉める音までが演出だ。",
      "静かな場所ほど、足元を見よう。", "予備の弾倉は、だいたい正しい。", "照明が赤くなったら、迷う時間は短い。",
      "次のカットは、できれば明るい場所で。", "地図の余白にも、危険は書かれていない。", "荒野は観客席を用意しない。",
      "開いたドアは、招待状ではない。", "聞こえない足音ほど、近くにいる。", "この街の案内板は、だいたい遅い。",
      "映画なら、ここで一度だけ後ろを見る。", "無線の雑音は、背景音にしておこう。", "照準と懐中電灯は、同じくらい大事。",
      "次の部屋の空気が変わる前に進もう。", "エンドロールは、生きてから考える。", "安全地帯にも、出口は必要だ。",
      "夜の廃墟は、説明を省きすぎる。", "帰還の文字が見えるまで、油断は保留。", "観測記録は、まだ続いている。"
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
      case KILL -> actor + "が" + pick(KILL_SCENES, sourceHash, 3) + safeDetail(detail, "感染者") + "を"
          + pick(KILL_VERBS, sourceHash, 1) + "！！\n" + joinPunchlines(
              pick(KILL_END, sourceHash, 2), pick(GENRE_STINGERS, sourceHash, 4),
              pick(KILL_CAMERA_BEATS, sourceHash, 5));
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
        : copy + " " + pick(TIMELINE_BEATS, sourceHash, 7);
  }

  private String safeDetail(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  long killVariantCapacity() {
    return (long) KILL_SCENES.size()
        * KILL_VERBS.size()
        * KILL_END.size()
        * GENRE_STINGERS.size()
        * KILL_CAMERA_BEATS.size();
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
}
