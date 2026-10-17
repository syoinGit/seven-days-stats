package com.yuki.rubinco.account.service;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PortalProfileService {
  public static final List<String> AVATARS = List.of("comet", "orbit", "peak", "bloom", "wave", "spark", "moon", "leaf");
  private final M_WebAccountRepository accounts;
  private final CurrentWebAccountService currentAccounts;

  public PublicProfile profile(M_WebAccount account) {
    String key = account.getAvatarKey() != null && AVATARS.contains(account.getAvatarKey()) ? account.getAvatarKey()
        : AVATARS.get(Math.floorMod(account.getId() == null ? 0L : account.getId(), AVATARS.size()));
    return new PublicProfile(account.getDisplayName() == null || account.getDisplayName().isBlank()
        ? account.getLoginId() : account.getDisplayName(), key, url(key));
  }

  @Transactional(readOnly = true)
  public Directory directory() {
    Map<Long, PublicProfile> players = new LinkedHashMap<>();
    Map<String, PublicProfile> authors = new LinkedHashMap<>();
    accounts.findAllByOrderByLoginIdAsc().stream()
        .filter(account -> account.isEnabled() && !"VIEWER".equals(account.getRole()))
        .forEach(account -> {
          var profile = profile(account);
          if (account.getPlayerId() != null) players.put(account.getPlayerId(), profile);
          authors.put("local:" + account.getLoginId(), profile);
          if (account.getCognitoSub() != null) authors.put(account.getCognitoSub(), profile);
        });
    return new Directory(Map.copyOf(players), Map.copyOf(authors));
  }

  public String avatar(Long playerId, String creator, Directory directory, boolean guest) {
    if (guest) return url("guest");
    PublicProfile profile = playerId == null ? null : directory.players().get(playerId);
    if (profile == null && creator != null) profile = directory.authors().get(creator);
    if (profile != null) return profile.avatarUrl();
    return playerId == null ? url("observer") : url(AVATARS.get(Math.floorMod(playerId, AVATARS.size())));
  }

  public String authorName(Long playerId, String creator, String fallback, Directory directory, boolean guest) {
    if (guest) return playerId == null && creator == null ? "アクティビティ" : "プレイヤー";
    PublicProfile profile = playerId == null ? null : directory.players().get(playerId);
    if (profile == null && creator != null) profile = directory.authors().get(creator);
    return profile != null ? profile.displayName()
        : fallback == null || fallback.isBlank() ? "Rubinco" : fallback;
  }

  public String url(String key) {
    return "/img/avatars/" + key + ".svg";
  }

  @Transactional
  public void update(Authentication authentication, String displayName, String avatarKey) {
    var account = currentAccounts.current(authentication)
        .filter(value -> !"VIEWER".equals(value.getRole()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    String name = displayName == null ? "" : displayName.strip();
    if (name.isEmpty() || name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("表示名は1〜40文字で入力してください。");
    }
    if (avatarKey == null || !AVATARS.contains(avatarKey)) throw new IllegalArgumentException("アイコンを選択してください。");
    account.setDisplayName(name);
    account.setAvatarKey(avatarKey);
    accounts.save(account);
  }

  public record PublicProfile(String displayName, String avatarKey, String avatarUrl) { }
  public record Directory(Map<Long, PublicProfile> players, Map<String, PublicProfile> authors) { }
}
