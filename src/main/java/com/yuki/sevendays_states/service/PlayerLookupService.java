package com.yuki.sevendays_states.service;

import com.yuki.sevendays_states.entity.M_Player;
import com.yuki.sevendays_states.repository.M_PlayerRepository;
import com.yuki.sevendays_states.util.PlayerIdentity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves one logical player across the identity formats emitted by the game and server logs. */
@Service
@RequiredArgsConstructor
public class PlayerLookupService {

  private final M_PlayerRepository playerRepository;

  public Optional<M_Player> findExisting(
      String playerKey,
      String platform,
      String userId,
      String nativePlatform,
      String nativeUserId) {
    List<String> candidateKeys = PlayerIdentity.candidatePlayerKeys(
        platform, userId, nativePlatform, nativeUserId);
    if (!candidateKeys.contains(playerKey)) {
      candidateKeys.addFirst(playerKey);
    }
    List<M_Player> byKey = playerRepository.findByPlayerKeyInOrderByIdAsc(candidateKeys);
    if (!byKey.isEmpty()) {
      return Optional.of(byKey.getFirst());
    }
    Optional<M_Player> byPlatformUser = playerRepository
        .findFirstByPlatformIgnoreCaseAndUserIdOrderByIdAsc(platform, userId);
    if (byPlatformUser.isPresent()) {
      return byPlatformUser;
    }
    if (nativePlatform == null || nativeUserId == null) {
      return Optional.empty();
    }
    return playerRepository.findFirstByNativePlatformIgnoreCaseAndNativeUserIdOrderByIdAsc(
        nativePlatform, nativeUserId);
  }

}
