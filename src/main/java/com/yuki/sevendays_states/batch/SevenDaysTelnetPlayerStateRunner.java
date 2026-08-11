package com.yuki.sevendays_states.batch;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import com.yuki.sevendays_states.service.GameLogImportResult;
import com.yuki.sevendays_states.service.SevenDaysTelnetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SevenDaysTelnetPlayerStateRunner {

  private final SevenDaysDataProperties properties;
  private final SevenDaysTelnetService telnetService;

  @Scheduled(
      initialDelayString = "${app.sevendays.telnet.initial-delay:30s}",
      fixedDelayString = "${app.sevendays.telnet.lp-interval:60s}")
  public void scheduledPlayerStateFetch() {
    if (!properties.telnet().enabled()) {
      return;
    }
    GameLogImportResult result = telnetService.fetchPlayerList();
    log.info("7DTD telnet lp import finished. {}", result);
  }
}
