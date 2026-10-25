package com.yuki.rubinco.guild;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.guild.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class GuildRunner {
  private final GuildService guild;
  private final GuildInteractionService interactions;

  @Scheduled(cron = "0 0 21 * * *", zone = "Asia/Tokyo")
  public void birth() {
    try { guild.generateScheduled(); }
    catch (RuntimeException exception) { log.error("Guild birth transaction failed ({})", exception.getClass().getSimpleName()); }
  }

  /** A few minutes past each hour, after the day's visitors are in place. */
  @Scheduled(cron = "0 3 * * * *", zone = "Asia/Tokyo")
  public void interaction() {
    try { interactions.runHour(java.time.ZonedDateTime.now(GuildService.TOKYO)); }
    catch (RuntimeException exception) { log.error("Guild interaction transaction failed ({})", exception.getClass().getSimpleName()); }
  }

  @Scheduled(initialDelayString = "${app.guild.visit-initial-delay:30s}", fixedDelayString = "${app.guild.visit-check-interval:1m}")
  public void visits() {
    try { guild.prepareToday(); }
    catch (RuntimeException exception) { log.error("Guild visit transaction failed ({})", exception.getClass().getSimpleName()); }
  }
}
