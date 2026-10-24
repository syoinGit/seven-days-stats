package com.yuki.rubinco.worldprogress.service;

import com.yuki.rubinco.worldprogress.entity.M_GuideRule;
import java.util.List;
import java.util.Map;

/** Rule evaluation never treats an unknown state as satisfying any condition. */
public final class GuideRules {
  private GuideRules() { }

  public static boolean visible(List<M_GuideRule> rules, Map<Long, ProgressState> states) {
    for (M_GuideRule rule : rules) {
      boolean matches = matches(rule, states);
      if ("HIDE".equals(rule.getRuleType()) && matches) return false;
      if ("SHOW".equals(rule.getRuleType()) && !matches) return false;
    }
    return true;
  }

  private static boolean matches(M_GuideRule rule, Map<Long, ProgressState> states) {
    ProgressState state = states.getOrDefault(rule.getProgressDefinitionId(), ProgressState.UNKNOWN);
    return state != ProgressState.UNKNOWN && state.name().equals(rule.getRequiredState());
  }
}
