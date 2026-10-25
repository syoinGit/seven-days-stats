package com.yuki.rubinco.luminite.service;

import com.yuki.rubinco.luminite.ingest.TerrariaImportProperties;
import com.yuki.rubinco.worldprogress.ai.WorldAiFacts;
import com.yuki.rubinco.worldprogress.service.WorldProgressQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Facts for the Luminite AI: what members see on the public pages, nothing more. */
@Component
@RequiredArgsConstructor
public class LuminiteAiFacts implements WorldAiFacts {
  private final LuminiteQueryService luminite;
  private final WorldProgressQueryService progress;
  private final TerrariaImportProperties importProperties;

  @Override
  public String gameId() {
    return LuminiteQueryService.GAME_ID;
  }

  @Override
  public String gameTitle() {
    return "Terraria";
  }

  @Override
  public Optional<String> facts() {
    var context = luminite.context(false).filter(LuminiteQueryService.Context::hasData);
    if (context.isEmpty()) return Optional.empty();
    var ctx = context.get();
    var overview = progress.overview(gameId(), false, importProperties.staleAfter());
    long bosses = overview.items().stream().filter(item -> "BOSS".equals(item.category())).count();
    List<String> lines = new ArrayList<>();
    lines.add("ゲーム: Terraria（2Dの探索・建築アクション）");
    var world = luminite.world(ctx.summary());
    lines.add("ワールド: " + world.name() + "（" + world.size() + "・" + world.difficulty() + "・"
        + world.evil() + (world.hardMode() ? "・ハードモード" : "") + "）");
    luminite.highlights(ctx, bosses)
        .forEach(stat -> lines.add(stat.label() + ": " + stat.value() + "（" + stat.note() + "）"));
    if (!overview.items().isEmpty()) {
      lines.add("達成済みの進捗: " + String.join("、",
          overview.items().stream().map(WorldProgressQueryService.ProgressItem::label).toList()));
    }
    var events = luminite.events(ctx.summary());
    if (events.clock() != null) {
      lines.add("保存時のゲーム内時刻: " + events.clock() + (events.moon() == null ? "" : "、月: " + events.moon())
          + (events.active().isEmpty() ? "" : "、イベント: " + String.join("、", events.active())));
    }
    var items = luminite.items(ctx, null, "count", 0, 8).getContent();
    if (!items.isEmpty()) {
      lines.add("多く保管されているアイテム: " + String.join("、",
          items.stream().map(item -> item.name() + " " + item.total()).toList()));
    }
    var kills = luminite.bestiary(ctx, null, "kills").stream().filter(row -> row.kills() > 0).limit(5).toList();
    if (!kills.isEmpty()) {
      lines.add("図鑑の討伐数（多い順）: " + String.join("、",
          kills.stream().map(row -> row.name() + " " + row.kills()).toList()));
    }
    luminite.activity(ctx, 3).forEach(activity ->
        lines.add(activity.atLabel() + " " + activity.title() + " " + String.join(" / ", activity.details())));
    lines.add("注意: ワールドファイルの記録で、誰が何をしたかは分からない。");
    return Optional.of(String.join("\n", lines));
  }
}
