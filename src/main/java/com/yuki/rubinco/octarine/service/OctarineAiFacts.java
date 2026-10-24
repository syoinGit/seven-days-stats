package com.yuki.rubinco.octarine.service;

import com.yuki.rubinco.octarine.ingest.CoreKeeperImportProperties;
import com.yuki.rubinco.portal.service.GamePortalService;
import com.yuki.rubinco.worldprogress.ai.WorldAiFacts;
import com.yuki.rubinco.worldprogress.service.WorldProgressQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Facts for the Octarine AI: what members see on the public pages, nothing more. */
@Component
@RequiredArgsConstructor
public class OctarineAiFacts implements WorldAiFacts {
  private final OctarineQueryService octarine;
  private final WorldProgressQueryService progress;
  private final GamePortalService portal;
  private final CoreKeeperImportProperties importProperties;

  @Override
  public String gameId() {
    return OctarineQueryService.GAME_ID;
  }

  @Override
  public String actorName() {
    return portal.card(gameId()).displayName() + "観測AI";
  }

  @Override
  public Optional<String> facts() {
    var context = octarine.context(false).filter(OctarineQueryService.Context::hasData);
    if (context.isEmpty()) return Optional.empty();
    var ctx = context.get();
    var overview = progress.overview(gameId(), false, importProperties.staleAfter());
    List<String> lines = new ArrayList<>();
    lines.add("ゲーム: Core Keeper（地下を掘って進む協力サバイバル）");
    octarine.highlights(ctx, overview.achievedCount())
        .forEach(stat -> lines.add(stat.label() + ": " + stat.value() + "（" + stat.note() + "）"));
    if (!overview.items().isEmpty()) {
      lines.add("達成済みの進捗: " + String.join("、",
          overview.items().stream().map(WorldProgressQueryService.ProgressItem::label).toList()));
    }
    var items = octarine.items(ctx, null, "slots", 0, 8).getContent();
    if (!items.isEmpty()) {
      lines.add("よく保管されているアイテム（枠の数）: " + String.join("、",
          items.stream().map(item -> item.name() + " " + item.slots() + "枠").toList()));
    }
    octarine.activity(ctx, 2).forEach(activity ->
        lines.add(activity.atLabel() + " " + activity.title() + " " + String.join(" / ", activity.details())));
    lines.add("注意: 数えているのは、プレイヤーが出し入れしたチェストなどの中身だけ。アイテムの数は素材なら個数、"
        + "装備なら耐久値。保存データの記録で、誰が何をしたかは分からない。");
    return Optional.of(String.join("\n", lines));
  }
}
