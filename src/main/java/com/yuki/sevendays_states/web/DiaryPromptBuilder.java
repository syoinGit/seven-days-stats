package com.yuki.sevendays_states.web;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DiaryPromptBuilder {

  public String build(
      LocalDate date,
      String gameDay,
      List<DiaryMaintenanceService.PlayerDay> players,
      List<DiaryMaintenanceService.EventCount> events,
      List<DiaryMaintenanceService.EventCount> enemies,
      List<String> pois,
      DiaryMaintenanceService.BloodMoonContext bloodMoon,
      DiaryMaintenanceService.XpSummary xp) {
    List<String> lines = new ArrayList<>();
    lines.add("# WATCHPOINT 冒険日誌・生成プロンプト");
    lines.add("");
    lines.add("## 出力ヘッダー");
    lines.add("# WATCHPOINT - 冒険日誌");
    lines.add(gameDay + "　" + date);
    lines.add("");
    lines.add("## コンセプトと文体");
    lines.add("これはゲームのプレイログや統計レポートではない。終末世界を生きる生存者が、その日の出来事を書き残した連載形式の日誌として書く。");
    lines.add("落ち着いた大人向けの日本語で、洋画・海外ドラマのような現実感のある荒廃世界を描く。データを列挙・解説せず、事実から情景、判断、緊張、安堵を組み立てる。");
    lines.add("毎回同じ導入・締め・比喩を避け、翌日も読みたくなる余韻を残す。ゲーム用語や数値は必要なものだけ物語へ自然に溶かす。");
    lines.add("記録にない会話、負傷、死亡、因果関係、感情を断定して創作しない。不明な事実を補完しない。観測事実と、そこから自然に描ける情景を混同しない。");
    lines.add("");
    lines.add("## 日誌のテーマと構成");
    lines.add("当日のデータから最も印象的な出来事を一つ選び、それを日誌全体の中心テーマにする。複数の出来事を均等に並べたダイジェストにはしない。");
    lines.add("テーマ例: 探索初日、物資不足、住宅街の制圧、軍施設への遠征、ホードとの遭遇、静かな一日、補給の日、Blood Moon準備、新たなトレーダーとの接触。データに合う主題を選び、今回の本文内で同じ趣旨を繰り返さない。");
    lines.add("訪問POIを一覧として説明しない。代表的な場所を数か所だけ選び、焼け落ちた住宅、軍施設、地下シェルター、農場などを物語の舞台として自然に組み込む。");
    lines.add("プレイヤー紹介を毎回同じ順序・構成にしない。討伐数だけで人物を描かず、危険地帯を進んだ、探索を支えた、仲間の道を切り開いた、補給を担当した、遠征を牽引したなど、データが裏付ける範囲でその日の役割を表現する。");
    lines.add("プレイヤー間に順位や優劣を付けない。数値が多い者だけを主役にせず、集団として生き延びた一日を描く。");
    lines.add("締めは『明日も探索だ』のような定型句を避け、生存の安堵、残る不安、減った物資、失われていない希望など、終末世界で生きる人間の余韻を残す。締め方は毎回変える。");
    lines.add("");
    lines.add("## 重要な解釈ルール");
    lines.add("SLEEPER_SPAWNは戦闘数や一斉出現数ではなく、建物探索によって配置済みのスリーパーゾンビが目覚めた記録。『眠っていた感染者を起こした』『廃墟の奥で気配が動き出した』などと表現し、『同時に襲来した』『すべて討伐した』とは書かない。");
    lines.add("ゲーム内Dayが進んでいる場合は、拠点・装備・探索範囲の発展や強敵の増加を、当日のデータが裏付ける範囲で自然に反映する。");
    lines.add("Blood Moonの前日・当日・翌日は、防衛準備、物資確保、緊張、生還後の安堵などを当日の事実に沿って日誌の空気へ反映する。");
    lines.add("経験値は全プレイヤー共通の活動傾向として扱う。討伐XPが多ければ戦闘、採取XPが多ければ資源確保、探索・物資XPが多ければ探索を中心に描けるが、特定プレイヤーの成果とは断定しない。");
    lines.add("討伐数・遭遇数・XPなどの数値は事実の根拠として使う。本文では必要以上に羅列せず、数値から読み取れる一日の流れや空気を優先する。必要なら討伐数を記載してよいが、統計レポートの文章にはしない。");
    lines.add("乗り物距離は、位置と時刻の整合が取れて運転者を確認できた移動だけである。車種、同乗者、所有者、運転の目的は、このデータだけでは断定しない。");
    lines.add("");
    lines.add("## 当日の観測データ");
    lines.add("実日付: " + date);
    lines.add("ゲーム内時間: " + gameDay);
    lines.add("Blood Moon: " + bloodMoon.status());
    lines.add("参加プレイヤー:");
    players.forEach(player -> lines.add("- " + player.name() + ": 討伐" + player.kills()
        + "、遭遇" + player.encounters() + "、位置移動"
        + player.positionDistance().setScale(1, RoundingMode.HALF_UP)
        + "m、乗り物" + player.vehicleDistance().setScale(1, RoundingMode.HALF_UP)
        + "m、ログイン" + player.joins() + "回、開始地点 " + player.startPlace()
        + "、終了地点 " + player.endPlace()));
    lines.add("全プレイヤー共通XP:");
    lines.add("- 討伐XP: " + xp.kills());
    lines.add("- 採取XP: " + xp.harvest());
    lines.add("- 探索・物資XP: " + xp.loot());
    lines.add("注意: 位置移動には乗車中の移動が含まれる可能性があるため、乗り物距離と単純合算しない。");
    lines.add("注意: 開始・終了地点はログイン／ログアウトそのものの座標ではなく、その日の最初と最後に取得できた位置ログから求めた最寄りPOI。");
    lines.add("注意: 現在のログには建築専用XPがないため、採取XPを建築XPとして扱わない。");
    lines.add("主要イベント: " + eventSummary(events));
    lines.add("討伐した敵: " + eventSummary(enemies));
    lines.add("訪問POI: " + (pois.isEmpty() ? "未記録" : String.join("、", pois)));
    lines.add("");
    lines.add("## 出力指示");
    lines.add("上記だけを根拠に、選んだ一つのテーマを軸として、参加者それぞれの一日の流れと集団全体の動きが伝わる冒険日誌を作成する。見出しの後に読み物として本文を出力し、データ一覧・箇条書き・分析コメントは出力しない。");
    return String.join("\n", lines);
  }

  private String eventSummary(List<DiaryMaintenanceService.EventCount> counts) {
    return counts.isEmpty() ? "なし" : counts.stream()
        .map(count -> count.name() + " " + count.count() + "件")
        .collect(java.util.stream.Collectors.joining("、"));
  }
}
