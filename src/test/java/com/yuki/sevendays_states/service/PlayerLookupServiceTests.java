package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuki.sevendays_states.entity.M_Player;
import com.yuki.sevendays_states.repository.M_PlayerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlayerLookupServiceTests {

  @Mock
  private M_PlayerRepository playerRepository;

  @Test
  void prefersTheCanonicalIdentityKeyBeforeLegacyColumns() {
    M_Player player = new M_Player();
    player.setId(42L);
    when(playerRepository.findByPlayerKeyInOrderByIdAsc(anyList())).thenReturn(List.of(player));

    Optional<M_Player> found = service().findExisting(
        "EOS:eos-a", "EOS", "eos-a", "Steam", "steam-a");

    assertThat(found).containsSame(player);
    verify(playerRepository).findByPlayerKeyInOrderByIdAsc(
        List.of("EOS:eos-a", "Steam:steam-a"));
  }

  @Test
  void fallsBackToTheNativeIdentityWhenNoCanonicalOrPlatformMatchExists() {
    M_Player player = new M_Player();
    player.setId(7L);
    when(playerRepository.findByPlayerKeyInOrderByIdAsc(anyList())).thenReturn(List.of());
    when(playerRepository.findFirstByPlatformIgnoreCaseAndUserIdOrderByIdAsc("EOS", "eos-a"))
        .thenReturn(Optional.empty());
    when(playerRepository.findFirstByNativePlatformIgnoreCaseAndNativeUserIdOrderByIdAsc(
        "Steam", "steam-a")).thenReturn(Optional.of(player));

    Optional<M_Player> found = service().findExisting(
        "EOS:eos-a", "EOS", "eos-a", "Steam", "steam-a");

    assertThat(found).containsSame(player);
  }

  private PlayerLookupService service() {
    return new PlayerLookupService(playerRepository);
  }
}
