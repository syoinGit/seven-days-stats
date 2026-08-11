package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.yuki.sevendays_states.config.SevenDaysDataProperties;
import org.junit.jupiter.api.Test;

class SevenDaysTelnetCommandClientTests {

  @Test
  void broadcastsTextAsOneSafeSayCommand() {
    SevenDaysTelnetCommandClient client = spy(new SevenDaysTelnetCommandClient(
        mock(SevenDaysDataProperties.class)));
    doReturn(true).when(client).send("say \"WATCHPOINT: 生存者の'観測' 続き\\\\です\"");

    boolean sent = client.broadcast("WATCHPOINT: 生存者の\"観測\"\n続き\\です");

    assertThat(sent).isTrue();
    verify(client).send("say \"WATCHPOINT: 生存者の'観測' 続き\\\\です\"");
  }

  @Test
  void normalizesAiTextToOneLineBeforeSay() {
    SevenDaysTelnetCommandClient client = spy(new SevenDaysTelnetCommandClient(
        mock(SevenDaysDataProperties.class)));
    doReturn(true).when(client).send("say \"観測です。 続きです。\"");

    boolean sent = client.broadcast("観測です。\n\t続きです。\u2028");

    assertThat(sent).isTrue();
    verify(client).send("say \"観測です。 続きです。\"");
  }

  @Test
  void doesNotBroadcastBlankText() {
    SevenDaysTelnetCommandClient client = spy(new SevenDaysTelnetCommandClient(
        mock(SevenDaysDataProperties.class)));

    assertThat(client.broadcast("\n\r")).isFalse();
  }
}
