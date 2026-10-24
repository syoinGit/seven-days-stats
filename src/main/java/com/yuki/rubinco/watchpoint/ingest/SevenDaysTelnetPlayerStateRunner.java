package com.yuki.rubinco.watchpoint.ingest;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SevenDaysTelnetPlayerStateRunner {
  private static final String GAME_ID = "7dtd";

  private final SevenDaysDataProperties properties;
  private final SevenDaysTelnetService telnetService;
  private final ServerActivityGate gate;

  @Scheduled(
      initialDelayString = "${app.sevendays.telnet.initial-delay:30s}",
      fixedDelayString = "${app.sevendays.telnet.lp-interval:60s}")
  public void scheduledPlayerStateFetch() {
    if (!properties.telnet().enabled() || !gate.importAllowed("7dtd-telnet", GAME_ID)) {
      return;
    }
    GameLogImportResult result = telnetService.fetchPlayerList();
    log.info("7DTD telnet lp import finished. {}", result);
  }
}
