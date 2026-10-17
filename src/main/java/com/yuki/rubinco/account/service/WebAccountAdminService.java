package com.yuki.rubinco.account.service;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import com.yuki.rubinco.watchpoint.persistence.entity.M_Player;
import com.yuki.rubinco.watchpoint.persistence.repository.M_PlayerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebAccountAdminService {

  private final M_WebAccountRepository accountRepository;
  private final M_PlayerRepository playerRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional(readOnly = true)
  public AccountAdminView view() {
    var players = playerRepository.findAllByOrderByPlayerNameAsc();
    var names = players.stream().collect(java.util.stream.Collectors.toMap(M_Player::getId, M_Player::getPlayerName));
    List<AccountRow> accounts = accountRepository.findAllByOrderByLoginIdAsc().stream()
        .filter(account -> !"VIEWER".equals(account.getRole()))
        .map(account -> new AccountRow(
            account.getId(), account.getLoginId(), account.getRole(),
            account.getPlayerId() == null
                ? null
                : names.getOrDefault(account.getPlayerId(), "未連携"),
            account.isEnabled(), account.getPlayerId()))
        .toList();
    return new AccountAdminView(accounts, players);
  }

  @Transactional
  public void create(String loginId, String password, String role, Long playerId) {
    String normalizedLoginId = loginId == null ? "" : loginId.strip();
    String normalizedPassword = password == null ? "" : password;
    String normalizedRole = "ADMIN".equalsIgnoreCase(role) ? "ADMIN" : "PLAYER";
    if (!normalizedLoginId.matches("[A-Za-z0-9._-]{3,80}")) {
      throw new IllegalArgumentException("ログインIDは英数字・._-の3〜80文字で入力してください。");
    }
    if (normalizedPassword.length() < 8) {
      throw new IllegalArgumentException("パスワードは8文字以上で入力してください。");
    }
    if (accountRepository.existsByLoginId(normalizedLoginId)) {
      throw new IllegalArgumentException("そのログインIDはすでに使われています。");
    }
    if (playerId != null && playerRepository.findById(playerId).isEmpty()) {
      throw new IllegalArgumentException("指定されたゲームプレイヤーが見つかりません。");
    }
    if (playerId != null && accountRepository.findByPlayerId(playerId).isPresent()) {
      throw new IllegalArgumentException("そのゲームプレイヤーはすでに別アカウントへ紐付いています。");
    }
    M_WebAccount account = new M_WebAccount();
    account.setLoginId(normalizedLoginId);
    account.setPasswordHash(passwordEncoder.encode(normalizedPassword));
    account.setRole(normalizedRole);
    account.setPlayerId(playerId);
    account.setEnabled(true);
    try {
      accountRepository.save(account);
    } catch (DataIntegrityViolationException e) {
      throw new IllegalArgumentException("アカウントを作成できませんでした。入力を確認してください。", e);
    }
  }

  @Transactional
  public void linkPlayer(Long accountId, Long playerId) {
    var account = accountRepository.findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("ユーザーが見つかりません。"));
    if ("VIEWER".equals(account.getRole())) {
      throw new IllegalArgumentException("ゲストにはゲームプレイヤーを連携できません。");
    }
    if (playerId != null) {
      if (!playerRepository.existsById(playerId)) {
        throw new IllegalArgumentException("ゲームプレイヤーが見つかりません。");
      }
      if (accountRepository.findByPlayerId(playerId)
          .filter(other -> !other.getId().equals(accountId)).isPresent()) {
        throw new IllegalArgumentException("そのプレイヤーは別のユーザーと連携済みです。");
      }
    }
    account.setPlayerId(playerId);
    try {
      accountRepository.saveAndFlush(account);
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("連携を保存できませんでした。現在の連携状況を確認してください。", exception);
    }
  }

  @Transactional
  public void changePassword(Long accountId, String password, String passwordConfirmation) {
    if (accountId == null) {
      throw new IllegalArgumentException("変更対象のアカウントを選択してください。");
    }
    M_WebAccount account = accountRepository.findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("対象のアカウントが見つかりません。"));
    if ("VIEWER".equals(account.getRole())) {
      throw new IllegalArgumentException("ゲストアカウントのパスワードは変更できません。");
    }
    String normalizedPassword = password == null ? "" : password;
    if (normalizedPassword.length() < 8) {
      throw new IllegalArgumentException("パスワードは8文字以上で入力してください。");
    }
    if (!normalizedPassword.equals(passwordConfirmation)) {
      throw new IllegalArgumentException("確認用パスワードが一致しません。");
    }
    account.setPasswordHash(passwordEncoder.encode(normalizedPassword));
    accountRepository.save(account);
  }

  public record AccountAdminView(List<AccountRow> accounts, List<M_Player> players) {
  }

  public record AccountRow(Long id, String loginId, String role, String playerName, boolean enabled, Long playerId) {
  }
}
