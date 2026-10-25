package com.yuki.rubinco.guild;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * categoryWindow / categoryCap: among the last categoryWindow residents, at most categoryCap may come
 * from memories of one category. lineageWeights: how often each name lineage is drawn.
 */
@ConfigurationProperties("app.guild")
public record GuildProperties(boolean enabled, int recentDays, double recentRatio, String publicUrl, int dailyLimit,
                              double femaleRatio, int categoryWindow, int categoryCap, Map<String, Integer> lineageWeights) {
  public GuildProperties {
    recentDays = recentDays <= 0 ? 30 : Math.min(recentDays, 3650);
    dailyLimit = dailyLimit <= 0 ? 10 : Math.min(dailyLimit, 50);
    categoryWindow = categoryWindow <= 0 ? 10 : Math.min(categoryWindow, 100);
    categoryCap = categoryCap <= 0 ? 3 : Math.min(categoryCap, categoryWindow);
    lineageWeights = lineageWeights == null ? Map.of() : Map.copyOf(lineageWeights);
    if (recentRatio < 0 || recentRatio > 1) throw new IllegalArgumentException("guild recent-ratio must be 0..1");
    if (femaleRatio < 0 || femaleRatio > 1) throw new IllegalArgumentException("guild female-ratio must be 0..1");
    publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    if (!publicUrl.isEmpty() && !publicUrl.matches("https?://[a-zA-Z0-9.:-]+/guild")) {
      throw new IllegalArgumentException("guild public-url must be an HTTP(S) URL ending in /guild");
    }
  }
}
