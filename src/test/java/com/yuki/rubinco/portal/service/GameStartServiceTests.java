package com.yuki.rubinco.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.yuki.rubinco.portal.entity.M_GamePortalState;
import com.yuki.rubinco.portal.repository.M_GamePortalStateRepository;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameStartServiceTests {
  @Mock private GamePortalService portalService;
  @Mock private M_GamePortalStateRepository stateRepository;
  @Mock private GameServerStartGateway gateway;

  private GameStartService service;

  @BeforeEach
  void setUp() {
    service = new GameStartService(portalService, stateRepository, gateway);
  }

  @Test
  void automaticServerNeverReachesDocker() {
    when(portalService.supports("terraria")).thenReturn(true);
    var result = service.requestStart("terraria", "member");
    assertThat(result.accepted()).isFalse();
    assertThat(result.message()).contains("自動起動");
    org.mockito.Mockito.verifyNoInteractions(gateway, stateRepository);
  }

  @Test
  void liveDockerStateAllowsRestartAfterAnExternalStopDespiteStaleDatabase() {
    M_GamePortalState state = new M_GamePortalState();
    state.setGameId("7dtd");
    state.setServerStatus("starting");
    when(portalService.supports("7dtd")).thenReturn(true);
    when(portalService.canStart("7dtd")).thenReturn(true);
    when(stateRepository.findForUpdate("7dtd")).thenReturn(Optional.of(state));
    when(gateway.liveStatus()).thenReturn(true);
    when(gateway.currentStatus("7dtd")).thenReturn("offline");
    when(gateway.requestStart("7dtd", "member"))
        .thenReturn(new GameServerStartGateway.StartResult(true, "starting", "accepted"));
    assertThat(service.requestStart("7dtd", "member").accepted()).isTrue();
    verify(gateway).requestStart("7dtd", "member");
  }

  @Test
  void maintenanceRemainsBlockedEvenWhenDockerIsSelected() {
    M_GamePortalState state = new M_GamePortalState();
    state.setGameId("7dtd");
    state.setServerStatus("maintenance");
    when(portalService.supports("7dtd")).thenReturn(true);
    when(portalService.canStart("7dtd")).thenReturn(true);
    when(stateRepository.findForUpdate("7dtd")).thenReturn(Optional.of(state));
    when(gateway.liveStatus()).thenReturn(true);
    assertThat(service.requestStart("7dtd", "member").accepted()).isFalse();
    verify(gateway, never()).currentStatus("7dtd");
    verify(gateway, never()).requestStart("7dtd", "member");
  }

  @Test
  void gatewayFailureReturnsSafeErrorWithoutChangingPersistedState() {
    M_GamePortalState state = new M_GamePortalState();
    state.setGameId("7dtd");
    state.setServerStatus("offline");
    when(portalService.supports("7dtd")).thenReturn(true);
    when(portalService.canStart("7dtd")).thenReturn(true);
    when(stateRepository.findForUpdate("7dtd")).thenReturn(Optional.of(state));
    when(gateway.requestStart("7dtd", "unsafe__actor"))
        .thenThrow(new IllegalStateException("sensitive provider detail"));

    var result = service.requestStart("7dtd", "unsafe\r\nactor");

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("error");
    assertThat(result.message()).doesNotContain("sensitive provider detail");
    assertThat(state.getServerStatus()).isEqualTo("offline");
    verify(stateRepository, never()).save(state);

    var retry = service.requestStart("7dtd", "unsafe\r\nactor");

    assertThat(retry.accepted()).isFalse();
    assertThat(retry.status()).isEqualTo("offline");
    assertThat(retry.message()).contains("連続送信");
    verify(gateway, times(1)).requestStart("7dtd", "unsafe__actor");
  }

  @Test
  void invalidPersistedStatusFailsClosedWithoutCallingGateway() {
    M_GamePortalState state = new M_GamePortalState();
    state.setGameId("7dtd");
    state.setServerStatus(null);
    when(portalService.supports("7dtd")).thenReturn(true);
    when(portalService.canStart("7dtd")).thenReturn(true);
    when(stateRepository.findForUpdate("7dtd")).thenReturn(Optional.of(state));

    var result = service.requestStart("7dtd", "member");

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("error");
    verify(gateway, never()).requestStart("7dtd", "member");
  }

  @Test
  void concurrentRequestsCallTheGatewayOnlyOnce() throws Exception {
    M_GamePortalState state = new M_GamePortalState();
    state.setGameId("7dtd");
    state.setServerStatus("offline");
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    when(portalService.supports("7dtd")).thenReturn(true);
    when(portalService.canStart("7dtd")).thenReturn(true);
    when(stateRepository.findForUpdate("7dtd")).thenReturn(Optional.of(state));
    when(gateway.requestStart("7dtd", "first-member")).thenAnswer(ignored -> {
      gatewayEntered.countDown();
      if (!releaseGateway.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test gateway was not released");
      }
      return new GameServerStartGateway.StartResult(true, "starting", "accepted");
    });

    CompletableFuture<GameServerStartGateway.StartResult> first = CompletableFuture.supplyAsync(
        () -> service.requestStart("7dtd", "first-member"));
    assertThat(gatewayEntered.await(2, TimeUnit.SECONDS)).isTrue();

    var duplicate = service.requestStart("7dtd", "second-member");
    releaseGateway.countDown();

    assertThat(duplicate.accepted()).isFalse();
    assertThat(duplicate.status()).isEqualTo("starting");
    assertThat(first.get(2, TimeUnit.SECONDS).accepted()).isTrue();
    verify(gateway, times(1)).requestStart("7dtd", "first-member");
  }
}
