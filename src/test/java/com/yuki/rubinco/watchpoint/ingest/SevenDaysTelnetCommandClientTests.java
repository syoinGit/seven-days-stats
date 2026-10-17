package com.yuki.rubinco.watchpoint.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import org.apache.commons.net.telnet.TelnetClient;
import org.junit.jupiter.api.Test;

class SevenDaysTelnetCommandClientTests {

  @Test
  void disabledTelnetDoesNotOpenACommandConnection() {
    SevenDaysDataProperties properties = new SevenDaysDataProperties(
        null, null, null, null, null, null, null, null, null, null);
    SevenDaysTelnetCommandClient client = new SevenDaysTelnetCommandClient(properties);

    try (var connections = mockConstruction(TelnetClient.class)) {
      assertThat(client.broadcast("PlayerA は AFK です")).isFalse();
      assertThat(connections.constructed()).isEmpty();
    }
  }

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
