package com.yuki.rubinco.guild;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * categoryWindow / categoryCap: among the last categoryWindow residents, at most categoryCap may come
 * from memories of one category. genderCap / occupationCap / lineageCap: a gender, occupation or name
 * lineage that this many of the last categoryWindow residents already have is not offered to the AI.
 * Interactions: one at each of interactionHours (JST), at most interactionDailyMax a day (never more than
 * three). selectionWeights: how participants are chosen (random, warm, cold, bond, thread, affiliation). interactionFallback: "template" saves a fixed scene when the AI fails, "skip" gives the hour up.
 * recentPairSlots: a pair seen in the last this-many interactions is drawn less often.
 */
@ConfigurationProperties("app.guild")
public record GuildProperties(boolean enabled, int recentDays, double recentRatio, String publicUrl, int dailyLimit,
                              int categoryWindow, int categoryCap, int genderCap, int occupationCap, int lineageCap,
                              java.util.List<Integer> interactionHours, int interactionDailyMax, Map<String, Integer> selectionWeights,
                              String interactionFallback, int recentPairSlots) {
  static final Set<String> SELECTIONS = Set.of("random", "warm", "cold", "bond", "thread", "affiliation");

  public GuildProperties {
    recentDays = recentDays <= 0 ? 30 : Math.min(recentDays, 3650);
    dailyLimit = dailyLimit <= 0 ? 20 : Math.min(dailyLimit, 50);
    categoryWindow = categoryWindow <= 0 ? 10 : Math.min(categoryWindow, 100);
    categoryCap = categoryCap <= 0 ? 3 : Math.min(categoryCap, categoryWindow);
    genderCap = genderCap <= 0 ? 7 : Math.min(genderCap, categoryWindow);
    occupationCap = occupationCap <= 0 ? 3 : Math.min(occupationCap, categoryWindow);
    lineageCap = lineageCap <= 0 ? 5 : Math.min(lineageCap, categoryWindow);
    interactionHours = interactionHours == null || interactionHours.isEmpty() ? java.util.List.of(20, 21, 22)
        : interactionHours.stream().distinct().sorted().toList();
    if (interactionHours.stream().anyMatch(hour -> hour == null || hour < 0 || hour > 23)) {
      throw new IllegalArgumentException("guild interaction-hours must be 0..23");
    }
    interactionDailyMax = interactionDailyMax <= 0 ? 3 : Math.min(interactionDailyMax, GuildInteractionService.DAILY_CAP);
    selectionWeights = selectionWeights == null || selectionWeights.isEmpty()
        ? Map.of("random", 3, "warm", 2, "cold", 1, "bond", 2, "thread", 2, "affiliation", 2) : Map.copyOf(selectionWeights);
    if (!SELECTIONS.containsAll(selectionWeights.keySet())) throw new IllegalArgumentException("guild selection-weights: unknown key");
    interactionFallback = interactionFallback == null || interactionFallback.isBlank() ? "template" : interactionFallback;
    if (!Set.of("template", "skip").contains(interactionFallback)) {
      throw new IllegalArgumentException("guild interaction-fallback must be template or skip");
    }
    recentPairSlots = recentPairSlots < 0 ? 3 : Math.min(recentPairSlots, 24);
    if (recentRatio < 0 || recentRatio > 1) throw new IllegalArgumentException("guild recent-ratio must be 0..1");
    publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    if (!publicUrl.isEmpty() && !publicUrl.matches("https?://[a-zA-Z0-9.:-]+/guild")) {
      throw new IllegalArgumentException("guild public-url must be an HTTP(S) URL ending in /guild");
    }
  }
}
