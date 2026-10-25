package com.yuki.rubinco.guild;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.guild")
public record GuildProperties(boolean enabled, int recentDays, double recentRatio, String publicUrl, int dailyLimit) {
  public GuildProperties {
    recentDays = recentDays <= 0 ? 30 : Math.min(recentDays, 3650);
    dailyLimit = dailyLimit <= 0 ? 10 : Math.min(dailyLimit, 50);
    if (recentRatio < 0 || recentRatio > 1) throw new IllegalArgumentException("guild recent-ratio must be 0..1");
    publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    if (!publicUrl.isEmpty() && !publicUrl.matches("https?://[a-zA-Z0-9.:-]+/guild")) {
      throw new IllegalArgumentException("guild public-url must be an HTTP(S) URL ending in /guild");
    }
  }
}
