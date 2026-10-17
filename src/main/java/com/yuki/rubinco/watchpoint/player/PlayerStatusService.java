package com.yuki.rubinco.watchpoint.player;

import com.yuki.rubinco.watchpoint.ingest.SevenDaysTelnetCommandClient;
import com.yuki.rubinco.watchpoint.persistence.entity.M_Player;
import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerCurrentState;
import com.yuki.rubinco.watchpoint.persistence.entity.T_PlayerStatus;
import com.yuki.rubinco.watchpoint.persistence.repository.M_PlayerRepository;
import com.yuki.rubinco.watchpoint.persistence.repository.T_PlayerCurrentStateRepository;
import com.yuki.rubinco.watchpoint.persistence.repository.T_PlayerStatusRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlayerStatusService {
  private final M_PlayerRepository playerRepository;
  private final T_PlayerStatusRepository statusRepository;
  private final SevenDaysTelnetCommandClient telnet;
  private final T_PlayerCurrentStateRepository currentStateRepository;

  @Transactional
  public Optional<StatusChange> updateByName(String playerName, String requestedStatus, String source) {
    if (playerName == null || requestedStatus == null) {
      return Optional.empty();
    }
    String status = PlayerStatusCatalog.normalize(requestedStatus);
    if (status == null) {
      return Optional.empty();
    }
    Optional<M_Player> player = playerRepository.findFirstByPlayerNameOrderByLastSeenAtDesc(playerName);
    if (player.isEmpty()) {
      return Optional.empty();
    }
    return updateForPlayer(player.get(), status, source);
  }

  @Transactional
  public Optional<StatusChange> updateByPlayerId(Long playerId, String requestedStatus, String source) {
    if (playerId == null || requestedStatus == null) {
      return Optional.empty();
    }
    String status = PlayerStatusCatalog.normalize(requestedStatus);
    if (status == null) {
      return Optional.empty();
    }
    return playerRepository.findById(playerId)
        .flatMap(player -> updateForPlayer(player, status, source));
  }

  private Optional<StatusChange> updateForPlayer(M_Player player, String status, String source) {
    boolean online = currentStateRepository.findByPlayerId(player.getId()).stream()
        .anyMatch(T_PlayerCurrentState::isOnline);
    if (!online) {
      return Optional.empty();
    }
    T_PlayerStatus row = statusRepository.findById(player.getId()).orElseGet(T_PlayerStatus::new);
    String previous = row.getStatus();
    row.setPlayerId(player.getId());
    row.setStatus(status);
    row.setSource(source == null || source.isBlank() ? "WEB" : source);
    row.setUpdatedAt(OffsetDateTime.now());
    statusRepository.save(row);
    if (!status.equals(previous)) {
      String message = player.getPlayerName() + " は " + PlayerStatusCatalog.label(status) + " です";
      telnet.broadcast(message);
      return Optional.of(new StatusChange(player, status, PlayerStatusCatalog.label(status)));
    }
    return Optional.empty();
  }

  public Optional<StatusChange> updateFromChat(String playerName, String command) {
    String status = PlayerStatusCatalog.fromChatCommand(command);
    return updateByName(playerName, status, "CHAT");
  }

  public record StatusChange(M_Player player, String status, String label) {
  }
}
