package com.yuki.rubinco.octarine.session;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin changes to Core Keeper players: account links and deletion on a member's request. */
@Service
@RequiredArgsConstructor
public class CoreKeeperPlayerAdminService {
  private final JdbcTemplate jdbc;

  @Transactional
  public void linkAccount(long ckPlayerId, Long accountId) {
    if (accountId != null) {
      Integer usable = jdbc.queryForObject("SELECT COUNT(*) FROM M_WEB_ACCOUNT WHERE id = ? AND role <> 'VIEWER'",
          Integer.class, accountId);
      if (usable == null || usable == 0) throw new IllegalArgumentException("ユーザーが見つかりません。");
    }
    try {
      if (jdbc.update("UPDATE M_CK_PLAYER SET web_account_id = ? WHERE id = ?", accountId, ckPlayerId) == 0) {
        throw new IllegalArgumentException("Core Keeperのプレイヤーが見つかりません。");
      }
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("そのユーザーは別のCore Keeperプレイヤーと連携済みです。", exception);
    }
  }

  /** Removes the player and all their sessions. A later connection starts a new record. */
  @Transactional
  public void delete(long ckPlayerId) {
    jdbc.update("DELETE FROM T_CK_SESSION WHERE ck_player_id = ?", ckPlayerId);
    if (jdbc.update("DELETE FROM M_CK_PLAYER WHERE id = ?", ckPlayerId) == 0) {
      throw new IllegalArgumentException("Core Keeperのプレイヤーが見つかりません。");
    }
  }
}
