package com.yuki.rubinco.guild;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * categoryWindow / categoryCap: among the last categoryWindow residents, at most categoryCap may come
 * from memories of one category. lineageWeights: how often each name lineage is drawn.
 * Interactions: one every interactionIntervalHours (JST hours divisible by it), at most
 * interactionDailyMax a day. selectionWeights: how participants are chosen (random, warm, cold, bond,
 * thread). interactionFallback: "template" saves a fixed scene when the AI fails, "skip" gives the hour up.
 * recentPairSlots: a pair seen in the last this-many interactions is drawn less often.
 */
@ConfigurationProperties("app.guild")
public record GuildProperties(boolean enabled, int recentDays, double recentRatio, String publicUrl, int dailyLimit,
                              double femaleRatio, int categoryWindow, int categoryCap, Map<String, Integer> lineageWeights,
                              int interactionIntervalHours, int interactionDailyMax, Map<String, Integer> selectionWeights,
                              String interactionFallback, int recentPairSlots) {
  static final Set<String> SELECTIONS = Set.of("random", "warm", "cold", "bond", "thread");

  public GuildProperties {
    recentDays = recentDays <= 0 ? 30 : Math.min(recentDays, 3650);
    dailyLimit = dailyLimit <= 0 ? 10 : Math.min(dailyLimit, 50);
    categoryWindow = categoryWindow <= 0 ? 10 : Math.min(categoryWindow, 100);
    categoryCap = categoryCap <= 0 ? 3 : Math.min(categoryCap, categoryWindow);
    lineageWeights = lineageWeights == null ? Map.of() : Map.copyOf(lineageWeights);
    interactionIntervalHours = interactionIntervalHours <= 0 ? 1 : Math.min(interactionIntervalHours, 24);
    interactionDailyMax = interactionDailyMax <= 0 ? 24 : Math.min(interactionDailyMax, 24);
    selectionWeights = selectionWeights == null || selectionWeights.isEmpty()
        ? Map.of("random", 3, "warm", 2, "cold", 1, "bond", 2, "thread", 2) : Map.copyOf(selectionWeights);
    if (!SELECTIONS.containsAll(selectionWeights.keySet())) throw new IllegalArgumentException("guild selection-weights: unknown key");
    interactionFallback = interactionFallback == null || interactionFallback.isBlank() ? "template" : interactionFallback;
    if (!Set.of("template", "skip").contains(interactionFallback)) {
      throw new IllegalArgumentException("guild interaction-fallback must be template or skip");
    }
    recentPairSlots = recentPairSlots < 0 ? 3 : Math.min(recentPairSlots, 24);
    if (recentRatio < 0 || recentRatio > 1) throw new IllegalArgumentException("guild recent-ratio must be 0..1");
    if (femaleRatio < 0 || femaleRatio > 1) throw new IllegalArgumentException("guild female-ratio must be 0..1");
    publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    if (!publicUrl.isEmpty() && !publicUrl.matches("https?://[a-zA-Z0-9.:-]+/guild")) {
      throw new IllegalArgumentException("guild public-url must be an HTTP(S) URL ending in /guild");
    }
  }
}
