package com.yuki.rubinco.worldprogress.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.yuki.rubinco.portal.service.ServerActivityGate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiMurmurServiceTests {
  private final ServerActivityGate gate = mock(ServerActivityGate.class);
  private final WorldAiPublishingService worlds = mock(WorldAiPublishingService.class);
  private final List<String> spoke = new ArrayList<>();

  /** A server with this many players on that answers with the given outcome. */
  private final class Server implements AiMurmurSource {
    final String id;
    int online;
    boolean enabled = true;
    Outcome outcome = Outcome.PUBLISHED;
    OffsetDateTime last;

    Server(String id, int online) {
      this.id = id;
      this.online = online;
    }

    @Override public String gameId() { return id; }
    @Override public boolean enabled() { return enabled; }
    @Override public int onlineCount() { return online; }
    @Override public Optional<OffsetDateTime> lastPublished() { return Optional.ofNullable(last); }

    @Override public Outcome murmur() {
      spoke.add(id);
      return outcome;
    }
  }

  private final Server sevenDays = new Server("7dtd", 0);
  private final Server coreKeeper = new Server("corekeeper", 0);

  @BeforeEach
  void allOnline() {
    doReturn(true).when(gate).aiAllowed("7dtd");
    doReturn(true).when(gate).aiAllowed("corekeeper");
  }

  private AiMurmurService service() {
    doReturn(List.of(coreKeeper)).when(worlds).sources();
    return new AiMurmurService(new WorldAiProperties(true, Duration.ofHours(1), 300), List.of(sevenDays), worlds, gate);
  }

  @Test
  void nobodyConnectedMeansNoMurmur() {
    assertThat(service().publishDue().status()).isEqualTo(AiMurmurService.Status.NOBODY_ONLINE);
    assertThat(spoke).isEmpty();
  }

  @Test
  void onlyAServerWithSomeoneOnIsPicked() {
    coreKeeper.online = 1;
    var result = service().publishDue();
    assertThat(result.status()).isEqualTo(AiMurmurService.Status.PUBLISHED);
    assertThat(result.gameId()).isEqualTo("corekeeper");
    assertThat(spoke).containsExactly("corekeeper");
  }

  @Test
  void aStoppedServerOrAnAiSwitchedOffIsNotPicked() {
    sevenDays.online = 3;
    coreKeeper.online = 1;
    doReturn(false).when(gate).aiAllowed("7dtd");
    coreKeeper.enabled = false;
    assertThat(service().publishDue().status()).isEqualTo(AiMurmurService.Status.NOBODY_ONLINE);
    assertThat(spoke).isEmpty();
  }

  @Test
  void oneMurmurPerIntervalAcrossEveryGame() {
    coreKeeper.online = 1;
    sevenDays.last = OffsetDateTime.now().minusMinutes(20);
    assertThat(service().publishDue().status()).isEqualTo(AiMurmurService.Status.NOT_DUE);
    sevenDays.last = OffsetDateTime.now().minusMinutes(61);
    assertThat(service().publishDue().status()).isEqualTo(AiMurmurService.Status.PUBLISHED);
  }

  @Test
  void theServerIsPickedAtRandomAndOneWithNothingToSayHandsTheTurnOn() {
    sevenDays.online = 2;
    coreKeeper.online = 1;
    Set<String> first = new HashSet<>();
    for (int i = 0; i < 60; i++) {
      spoke.clear();
      service().publishDue();
      first.add(spoke.getFirst());
    }
    assertThat(first).containsExactlyInAnyOrder("7dtd", "corekeeper");

    spoke.clear();
    sevenDays.outcome = AiMurmurSource.Outcome.NO_DATA;
    for (int i = 0; i < 20; i++) assertThat(service().publishDue().gameId()).isEqualTo("corekeeper");
    coreKeeper.outcome = AiMurmurSource.Outcome.NO_DATA;
    assertThat(service().publishDue().status()).isEqualTo(AiMurmurService.Status.NO_DATA);
  }

  @Test
  void aFailureEndsTheTurnWithoutTryingAnotherServer() {
    sevenDays.online = 1;
    sevenDays.outcome = AiMurmurSource.Outcome.FAILED;
    var result = service().publishDue();
    assertThat(result.status()).isEqualTo(AiMurmurService.Status.FAILED);
    assertThat(result.gameId()).isEqualTo("7dtd");
  }
}
