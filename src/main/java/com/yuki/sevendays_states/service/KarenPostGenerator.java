package com.yuki.sevendays_states.service;

import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import org.springframework.stereotype.Service;

/** Creates Karen's fictional life independently from real player and server observations. */
@Service
public class KarenPostGenerator {

  private static final List<Poi> POIS = List.of(
      new Poi("Crack-A-Book", "a ruined Crack-A-Book bookstore with dusty bookshelves"),
      new Poi("Working Stiff Tools", "an abandoned Working Stiff Tools hardware store with tool crates"),
      new Poi("Shamway Foods", "a looted Shamway Foods supermarket with empty shelves"),
      new Poi("Shotgun Messiah", "a fortified Shotgun Messiah gun store"),
      new Poi("Pass-N-Gas", "a dusty Pass-N-Gas station beside a cracked highway"),
      new Poi("Pop-N-Pills", "an abandoned Pop-N-Pills pharmacy"));

  private static final List<String> EXPLORATION_LINES = List.of(
      "%s寄ってきた📦\n今日はまだ当たり残ってた✨",
      "%sを探索中。\nこういう寄り道、だいたい正解✌️",
      "%sで掘り出し物みっけ👀\nバッグの空きは正義。",
      "%s、見た目より中が広かった😂\n無事に帰るまでが探索です。",
      "%sを一周してきた！\nゾンビより先に戦利品を確保✨",
      "%s、本日の収穫あり📚\n来た甲斐あったかも。",
      "%sの奥まで見てきた。\nちょっと怖いくらいが楽しいんだよね。",
      "%sで物資補給完了🙌\n今日のカレン、引き強め。",
      "%s、まだ使えるもの結構あるじゃん。\n拾える幸せ、大事。",
      "%sを軽く偵察。\n軽くのつもりがバッグ満タン😂");

  private static final List<String> DAILY_LINES = List.of(
      "今日のごはん🍖\nちゃんと焼いた肉ってだけで優勝。",
      "焚き火タイム🔥\n世界が終わっても晩ごはんは大事。",
      "今日は木こり🌲\n腕は終わったけど薪は増えた。",
      "武器のお手入れ完了🔧\n機嫌が悪くなる前に直すのがコツ。",
      "荷物整理したら缶詰が3つ出てきた。\n過去の私、えらい😂",
      "拠点の壁を補修中。\nDIYの難易度だけ世紀末。",
      "朝ごはんとコーヒー☕\n文明、まだギリ生きてる。",
      "採掘の日⛏️\n地上より地下の方が平和説ある。",
      "今日は少し休憩。\n走り続けるだけがサバイバルじゃないよね。",
      "水と食料の棚卸し完了。\nこういう地味な日が一番生存率上げる✌️",
      "焼き肉の匂い、たぶん半径1kmにバレた😂\n早めに食べよ。",
      "ブーツを直して、バッグも整理。\n明日の寄り道準備は万全✨");

  private static final List<String> TRAVEL_LINES = List.of(
      "今日はちょっと遠出🏍️\n帰り道まで燃料もってね、お願い。",
      "この夕焼け、終末とは思えない📷✨",
      "雪山まで来たけど寒すぎ😂\n景色は100点。",
      "砂漠を抜けて次の町へ。\n暑いし遠いし、でも楽しい。",
      "廃墟の屋上から一枚📷\n世界、広すぎない？",
      "朝焼けのうちに出発。\n早起きした私、かなり偉い✌️",
      "森の道をのんびり移動中🌲\nこういう時間も悪くない。",
      "高いところまで登ってきた！\n帰りのことは今考えない😂",
      "バイクと私、今日も絶好調🏍️\nたぶん。",
      "知らない道を見つけると曲がりたくなる。\n燃料だけが止めてくる。🛢️");

  private static final List<String> COMBAT_LINES = List.of(
      "ちょっとだけ荒れた😂\nでもまだ生きてる✌️",
      "徘徊ホードと鉢合わせ。\n予定外の運動、無事終了💥",
      "夜道は静かだと思った？\n私もそう思ってた😂",
      "戦闘おわり！\n弾薬と心拍数だけごっそり減った。",
      "今日のゾンビ、距離感近すぎ。\n丁重にお帰りいただきました🔨",
      "武器を持って出て正解だった日。\n備え、大事。ほんとに。",
      "騒がしい夜だったけど生存確認✌️\nシャワーが恋しい。",
      "あと一歩近かったら危なかった😂\nその一歩は渡しません。",
      "片付いたので記念投稿📷\n荒野は今日も通常営業。",
      "物音の正体、だいたい見に行かない方がいい。\n見に行ったけど。👀");

  private static final List<String> BLOOD_MOON_LINES = List.of(
      "赤い夜、無事に朝まで完走🌕\n今日の朝日ちょっと特別。",
      "血月モード終了！\n壁はボロボロ、私は元気✌️",
      "昨夜は空まで物騒だった😂\n生存報告だけ置いとく。",
      "赤い月と一枚撮ったけど、背景が全然静かじゃない📷",
      "血月明けの片付け中。\n夜より修理の方が長くない？",
      "あの夜を越えた朝のコーヒー、さすがに優勝☕");

  private static final List<String> SELFIE_LINES = List.of(
      "今日の生存確認📷✌️\nちゃんと元気です。",
      "戦利品と一枚✨\n重いけど置いて帰る選択肢はない。",
      "バイク休憩ついでに撮っとく🏍️📷",
      "山頂まで来た記念！\n風つよすぎて髪は諦めた😂",
      "ごはんできたので記念撮影🍖\n冷める前に食べます。",
      "戦闘直後の顔😂\nフィルターより先に水がほしい。",
      "屋上、光がいい感じだった📷✨",
      "今日も汚れてるけど、まあ生存者っぽいよね。",
      "新しい装備、似合ってる気がする✌️",
      "夕焼け逃げる前に一枚。\n撮影後は全力で帰ります📷");

  private static final List<String> GLAMOUR_LINES = List.of(
      "たまにはちゃんと撮っとく📷✨",
      "光がよかったので一枚。\n終末でも盛れる日は盛れる✌️",
      "今日はちょっとだけ映え重視📷\n装備はいつも通り。",
      "砂埃までいい感じに見える奇跡✨",
      "無事に生きてるし、写真くらい決めてもいいよね。",
      "お気に入りの装備で一枚📷\nこのあと普通に探索です。"
  );

  public KarenPost generate(LocalDate date) {
    SplittableRandom random = random(date, 0x4b4152454eL);
    KarenPostTheme theme = weightedTheme(random);
    Poi poi = POIS.get(random.nextInt(POIS.size()));
    return switch (theme) {
      case EXPLORATION -> new KarenPost(theme,
          EXPLORATION_LINES.get(random.nextInt(EXPLORATION_LINES.size())).formatted(poi.name()),
          poi.scene() + ", Karen searching shelves and holding a useful piece of loot",
          random.nextLong(858_993_460L));
      case DAILY_LIFE -> new KarenPost(theme, pick(DAILY_LINES, random),
          pick(List.of(
              "Karen cooking meat over a small campfire beside a survivor shelter",
              "Karen maintaining a rugged rifle at a workbench",
              "Karen organizing canned food and supplies inside a repaired safehouse",
              "Karen chopping firewood outside a dusty survivor base",
              "Karen mining stone in a rough underground tunnel"), random),
          random.nextLong(858_993_460L));
      case TRAVEL -> new KarenPost(theme, pick(TRAVEL_LINES, random),
          pick(List.of(
              "Karen beside a rugged motorcycle on a cracked desert highway at sunset",
              "Karen standing on a snowy mountain overlook with a ruined town far below",
              "Karen on the rooftop of an abandoned American building during golden hour",
              "Karen traveling through a pine forest road at sunrise",
              "Karen resting beside a motorcycle near a ruined town"), random),
          random.nextLong(858_993_460L));
      case COMBAT -> new KarenPost(theme, pick(COMBAT_LINES, random),
          pick(List.of(
              "Karen catching her breath after a zombie fight, weapon ready, defeated zombies in the distance",
              "Karen confronting a small wandering zombie horde on an abandoned street",
              "Karen holding a melee weapon in a dark ruined store after combat",
              "Karen reloading behind cover during a tense night encounter"), random),
          random.nextLong(858_993_460L));
      case BLOOD_MOON -> new KarenPost(theme, pick(BLOOD_MOON_LINES, random),
          "Karen taking a daring survivor selfie outside a fortified base beneath a dramatic red blood moon, distant zombies only",
          random.nextLong(858_993_460L));
      case SELFIE -> new KarenPost(theme, pick(SELFIE_LINES, random),
          pick(List.of(
              "arm-length survivor selfie beside a rugged motorcycle",
              "arm-length survivor selfie on a ruined rooftop with the town behind her",
              "arm-length survivor selfie with a loot bag and scavenged supplies",
              "arm-length survivor selfie at a mountain overlook",
              "arm-length survivor selfie beside a campfire meal"), random),
          random.nextLong(858_993_460L));
      case GLAMOUR_SELFIE -> new KarenPost(theme, pick(GLAMOUR_LINES, random),
          "tasteful confident arm-length lifestyle selfie in warm sunset light, fully clothed practical survivor outfit, non-explicit",
          random.nextLong(858_993_460L));
    };
  }

  private KarenPostTheme weightedTheme(SplittableRandom random) {
    int total = 0;
    for (KarenPostTheme theme : KarenPostTheme.values()) total += theme.weight();
    int selected = random.nextInt(total);
    for (KarenPostTheme theme : KarenPostTheme.values()) {
      selected -= theme.weight();
      if (selected < 0) return theme;
    }
    return KarenPostTheme.DAILY_LIFE;
  }

  private String pick(List<String> values, SplittableRandom random) {
    return values.get(random.nextInt(values.size()));
  }

  static SplittableRandom random(LocalDate date, long salt) {
    long seed = date.toEpochDay() * 0x9E3779B97F4A7C15L ^ salt;
    return new SplittableRandom(seed);
  }

  public record KarenPost(KarenPostTheme theme, String body, String imageScene, long imageSeed) { }

  private record Poi(String name, String scene) { }
}
