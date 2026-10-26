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
  private final GuildDungeon dungeon;
  private final GuildTalks talks;

  @Scheduled(cron = "0 0 21 * * *", zone = "Asia/Tokyo")
  public void birth() {
    try { guild.generateScheduled(); }
    catch (RuntimeException exception) { log.error("Guild birth transaction failed ({})", exception.getClass().getSimpleName()); }
  }

  /** A few minutes past each hour and half hour, after the day's visitors are in place; only the configured hours act. */
  @Scheduled(cron = "0 3,33 * * * *", zone = "Asia/Tokyo")
  public void interaction() {
    try { interactions.runHour(java.time.ZonedDateTime.now(GuildService.TOKYO)); }
    catch (RuntimeException exception) { log.error("Guild interaction transaction failed ({})", exception.getClass().getSimpleName()); }
  }

  /** Every minute; the party leaves once, between the departure time and the end of its run, is retold by the AI, and is settled once back. */
  @Scheduled(cron = "30 * * * * *", zone = "Asia/Tokyo")
  public void dungeon() {
    try { dungeon.departIfDue(java.time.ZonedDateTime.now(GuildService.TOKYO)); }
    catch (RuntimeException exception) { log.error("Guild dungeon transaction failed ({})", exception.getClass().getSimpleName()); }
    // Right after leaving, the AI retells the run (once; outside the departure's transaction and lock).
    try { dungeon.retellDue(java.time.ZonedDateTime.now(GuildService.TOKYO)); }
    catch (RuntimeException exception) { log.error("Guild dungeon retelling failed ({})", exception.getClass().getSimpleName()); }
    try { dungeon.settleDue(java.time.ZonedDateTime.now(GuildService.TOKYO)); }
    catch (RuntimeException exception) { log.error("Guild dungeon settle failed ({})", exception.getClass().getSimpleName()); }
  }

  /** The evening's talk with a resident at 22:30, tried again at 22:40 and 22:50 if the AI could not write it. */
  @Scheduled(cron = "0 30,40,50 22 * * *", zone = "Asia/Tokyo")
  public void talk() {
    try { log.info("Guild talk: {}", talks.writeScheduled(java.time.ZonedDateTime.now(GuildService.TOKYO))); }
    catch (RuntimeException exception) { log.error("Guild talk failed ({})", exception.getClass().getSimpleName()); }
  }

  @Scheduled(initialDelayString = "${app.guild.visit-initial-delay:30s}", fixedDelayString = "${app.guild.visit-check-interval:1m}")
  public void visits() {
    try { guild.prepareToday(); }
    catch (RuntimeException exception) { log.error("Guild visit transaction failed ({})", exception.getClass().getSimpleName()); }
  }
}
