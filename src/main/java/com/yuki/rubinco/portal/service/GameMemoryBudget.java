package com.yuki.rubinco.portal.service;

import com.yuki.rubinco.portal.config.GameCatalogProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Admission uses configured reservations, including startup, rather than transient RSS. */
@Component
@RequiredArgsConstructor
public class GameMemoryBudget {
  private static final Set<String> KNOWN = Set.of(
      "offline", "online", "starting", "stopping", "maintenance");
  private final GameCatalogProperties properties;

  public List<String> gameIds() {
    return properties.getCatalog().stream().map(GameCatalogProperties.Game::getId).toList();
  }

  public Snapshot evaluate(Map<String, String> statuses) {
    boolean known = true;
    long reserved = 0;
    for (var game : properties.getCatalog()) {
      String status = statuses.get(game.getId());
      if (status == null || !KNOWN.contains(status)) known = false;
      if (!"offline".equals(status)) reserved += game.getMemoryMib();
    }
    long budget = properties.getMemoryBudgetMib();
    Map<String, Allocation> allocations = new LinkedHashMap<>();
    for (var game : properties.getCatalog()) {
      String reason = "";
      if (!known) {
        reason = "サーバー状態を確認できないため起動できません。";
      } else if (game.getMemoryMib() > budget) {
        reason = "必要なメモリ枠がゲーム用上限を超えています。";
      } else if (reserved + game.getMemoryMib() > budget) {
        reason = "メモリ枠が不足しています。他のゲームの停止が必要です。";
      }
      boolean allowed = game.isManualStart() && "offline".equals(statuses.get(game.getId()))
          && reason.isEmpty();
      allocations.put(game.getId(), new Allocation(game.getMemoryMib(), allowed, reason));
    }
    return new Snapshot(budget, reserved, known, Map.copyOf(allocations));
  }

  public record Allocation(long requiredMiB, boolean startAllowed, String reason) {
    public String requiredLabel() { return label(requiredMiB); }
  }

  public record Snapshot(long budgetMiB, long reservedMiB, boolean known,
                         Map<String, Allocation> allocations) {
    public String budgetLabel() { return label(budgetMiB); }
    public String reservedLabel() { return known ? label(reservedMiB) : "未確認"; }
  }

  private static String label(long mib) {
    return String.format(Locale.ROOT, "%.1f GiB", mib / 1024.0);
  }
}
