package com.yuki.rubinco.guild;

import java.util.List;

/**
 * The places residents gather: the unattended guild in the middle of the world (ten seats; the dungeon leaves from
 * here) and one tavern in each of the three nations (five seats each, for residents who live there). Seats are
 * numbered once across every place, so a day's seat numbers never collide.
 */
public final class GuildPlaces {
  public static final String GUILD = "guild";

  /** nation: the village key whose residents come here, null for the guild. base: the first seat number. */
  public record Place(String key, String name, String nation, String nationName, int seats, int base,
                      List<String> morning, List<String> afternoon, List<String> evening) {
    public boolean guild() {
      return nation == null;
    }

    /** "樫の盾亭（グランフォード）", or the guild's name. */
    public String title() {
      return guild() ? name : name + "（" + nationName + "）";
    }

    public String line(int seat, String phase) {
      var lines = switch (phase) {
        case "morning" -> morning;
        case "afternoon" -> afternoon;
        default -> evening;
      };
      return lines.get(seat - base);
    }
  }

  public static final Place CENTER = new Place(GUILD, "無人の冒険者ギルド", null, null, 10, 0,
      List.of("窓辺で朝のパンを食べている。", "暖炉のそばで旅装を整えている。", "卓の上で今日の予定を考えている。", "静かに手を合わせている。", "入り口で靴の泥を落としている。",
          "奥の卓で地図を広げている。", "湯気の立つ茶をすすっている。", "掲示板の前で背伸びをしている。", "眠そうに目をこすっている。", "暖炉に薪をくべている。"),
      List.of("卓で道具の手入れをしている。", "掲示板の依頼を眺めている。", "昼食のスープをゆっくり味わっている。", "記録棚で本をめくっている。", "カウンターで休憩している。",
          "奥の卓で手紙を書いている。", "窓辺でうたた寝をしている。", "隣の卓の話に耳を傾けている。", "パンを半分に割っている。", "荷物の中身を数えている。"),
      List.of("暖炉を眺めながら温かい飲み物を飲んでいる。", "カウンターで夕食を待っている。", "卓で明日の支度をしている。", "本を閉じ、静かに休んでいる。", "窓の外に灯る明かりを眺めている。",
          "奥の卓で杯を傾けている。", "今日の出来事を思い返している。", "小さく鼻歌を歌っている。", "煮込みのおかわりを頼んでいる。", "ろうそくの火を見つめている。"));

  public static final List<Place> TOWNS = List.of(
      new Place("grantford", "樫の盾亭", "grantford", "グランフォード", 5, 10,
          List.of("焼きたての黒パンにバターを塗っている。", "壁の古い盾を見上げている。", "麦粥をふうふう冷ましている。", "朝の鐘の音に耳を澄ませている。", "長卓で畑の話をしている。"),
          List.of("エールを片手に市場の噂を聞いている。", "鎧の留め金を締め直している。", "長卓で羊皮紙の帳面をつけている。", "窓から見える麦畑を眺めている。", "肉の煮込みを待っている。"),
          List.of("暖炉の前で杯を掲げている。", "吟遊詩人の歌の続きを口ずさんでいる。", "領主様の噂話に聞き入っている。", "旗の下の席で静かに飲んでいる。", "今日の収穫を数えている。")),
      new Place("elmwood", "苔灯りの宿", "elmwood", "エルムウッド", 5, 15,
          List.of("木の実の粥を食べている。", "窓辺で小鳥にパンくずをやっている。", "摘んできた薬草を束ねている。", "朝露のついた外套を干している。", "根の階段に腰かけてあくびをしている。"),
          List.of("香草茶をゆっくり飲んでいる。", "弓の弦を張り替えている。", "苔のランプの手入れをしている。", "森で見かけた足跡の話をしている。", "蜂蜜をなめて目を細めている。"),
          List.of("光る苔の明かりを眺めている。", "精霊へ小さな祈りを捧げている。", "木の葉の笛を吹いている。", "梟の声に耳を傾けている。", "焚き火の煙の行方を見ている。")),
      new Place("tsukikage", "月見茶屋 朧", "tsukikage", "月影郷", 5, 20,
          List.of("縁側で白湯をすすっている。", "障子越しの朝日を浴びている。", "おにぎりを頬張っている。", "神棚に手を合わせている。", "畳の上で刀の手入れをしている。"),
          List.of("団子を片手に茶をすすっている。", "将棋盤をにらんでいる。", "風鈴の音に耳を澄ませている。", "手ぬぐいで汗をぬぐっている。", "筆で文をしたためている。"),
          List.of("提灯の明かりの下で盃を傾けている。", "縁側から月を眺めている。", "三味線の音に聞き入っている。", "湯豆腐をつついている。", "灯籠の火を見つめている。")));

  public static final List<Place> ALL = List.of(CENTER, TOWNS.get(0), TOWNS.get(1), TOWNS.get(2));

  private GuildPlaces() { }

  /** The place with this key, or null. */
  public static Place of(String key) {
    return ALL.stream().filter(place -> place.key().equals(key)).findFirst().orElse(null);
  }
}
