package com.yuki.rubinco.guild;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * categoryWindow / categoryCap: among the last categoryWindow residents, at most categoryCap may come
 * from memories of one category. genderCap / occupationCap / lineageCap: a gender, occupation or name
 * lineage that this many of the last categoryWindow residents already have is not offered to the AI.
 * Interactions: one at each of interactionHours (JST), two in each (on the hour and the half hour), at most interactionDailyMax a
 * day (never more than twelve). selectionWeights: how participants are chosen (random, warm, cold, bond, thread, affiliation). interactionFallback: "template" saves a fixed scene when the AI fails, "skip" gives the hour up.
 * recentPairSlots: a pair seen in the last this-many interactions is drawn less often.
 * dungeonDeparture / dungeonStageMinutes: when (JST) the day's party leaves for a dungeon, and how many minutes apart its
 * four stages come out; the run must end the same day.
 */
@ConfigurationProperties("app.guild")
public record GuildProperties(boolean enabled, int recentDays, double recentRatio, String publicUrl, int dailyLimit,
                              int categoryWindow, int categoryCap, int genderCap, int occupationCap, int lineageCap,
                              java.util.List<Integer> interactionHours, int interactionDailyMax, Map<String, Integer> selectionWeights,
                              String interactionFallback, int recentPairSlots,
                              java.time.LocalTime dungeonDeparture, int dungeonStageMinutes) {
  static final int DAILY_LIMIT_MAX = 5;
  static final Set<String> SELECTIONS = Set.of("random", "warm", "cold", "bond", "thread", "affiliation");

  public GuildProperties {
    recentDays = recentDays <= 0 ? 30 : Math.min(recentDays, 3650);
    // At most five new residents a day, whatever GUILD_DAILY_LIMIT says.
    dailyLimit = dailyLimit <= 0 ? DAILY_LIMIT_MAX : Math.min(dailyLimit, DAILY_LIMIT_MAX);
    categoryWindow = categoryWindow <= 0 ? 10 : Math.min(categoryWindow, 100);
    categoryCap = categoryCap <= 0 ? 3 : Math.min(categoryCap, categoryWindow);
    genderCap = genderCap <= 0 ? 7 : Math.min(genderCap, categoryWindow);
    occupationCap = occupationCap <= 0 ? 3 : Math.min(occupationCap, categoryWindow);
    lineageCap = lineageCap <= 0 ? 5 : Math.min(lineageCap, categoryWindow);
    interactionHours = interactionHours == null || interactionHours.isEmpty() ? java.util.List.of(18, 19, 20, 21, 22, 23)
        : interactionHours.stream().distinct().sorted().toList();
    if (interactionHours.stream().anyMatch(hour -> hour == null || hour < 0 || hour > 23)) {
      throw new IllegalArgumentException("guild interaction-hours must be 0..23");
    }
    interactionDailyMax = interactionDailyMax <= 0 ? 12 : Math.min(interactionDailyMax, GuildInteractionService.DAILY_CAP);
    selectionWeights = selectionWeights == null || selectionWeights.isEmpty()
        ? Map.of("random", 3, "warm", 2, "cold", 1, "bond", 2, "thread", 2, "affiliation", 2) : Map.copyOf(selectionWeights);
    if (!SELECTIONS.containsAll(selectionWeights.keySet())) throw new IllegalArgumentException("guild selection-weights: unknown key");
    interactionFallback = interactionFallback == null || interactionFallback.isBlank() ? "template" : interactionFallback;
    if (!Set.of("template", "skip").contains(interactionFallback)) {
      throw new IllegalArgumentException("guild interaction-fallback must be template or skip");
    }
    recentPairSlots = recentPairSlots < 0 ? 3 : Math.min(recentPairSlots, 24);
    dungeonDeparture = dungeonDeparture == null ? java.time.LocalTime.of(21, 50) : dungeonDeparture;
    dungeonStageMinutes = dungeonStageMinutes <= 0 ? 10 : Math.min(dungeonStageMinutes, 60);
    if (dungeonDeparture.plusMinutes(4L * dungeonStageMinutes).isBefore(dungeonDeparture)) {
      throw new IllegalArgumentException("guild dungeon run must end before midnight");
    }
    if (recentRatio < 0 || recentRatio > 1) throw new IllegalArgumentException("guild recent-ratio must be 0..1");
    publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    if (!publicUrl.isEmpty() && !publicUrl.matches("https?://[a-zA-Z0-9.:-]+/guild")) {
      throw new IllegalArgumentException("guild public-url must be an HTTP(S) URL ending in /guild");
    }
  }
}
