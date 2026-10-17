package com.yuki.rubinco.account.service;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("cognito")
@RequiredArgsConstructor
public class CognitoOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final M_WebAccountRepository accountRepository;
  private final PasswordEncoder passwordEncoder;

  @Override
  @Transactional
  public OidcUser loadUser(OidcUserRequest userRequest) {
    return synchronize(new OidcUserService().loadUser(userRequest));
  }

  OidcUser synchronize(OidcUser user) {
    String role = cognitoRole(user);
    String loginId = loginId(user);
    M_WebAccount account = accountRepository.findByCognitoSub(user.getSubject()).orElse(null);
    if (account == null) {
      account = accountRepository.findByLoginId(loginId).orElseGet(M_WebAccount::new);
      if (account.getCognitoSub() != null
          && !user.getSubject().equals(account.getCognitoSub())) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error("account_link_conflict"),
            "このWatchpointアカウントは別のSSOユーザーに紐付いています。");
      }
      account.setLoginId(loginId);
    }
    if (account.getId() != null && !account.isEnabled()) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error("account_disabled"), "このWatchpointアカウントは無効です。");
    }
    account.setCognitoSub(user.getSubject());
    account.setRole(role);
    if (account.getId() == null) account.setEnabled(true);
    if (account.getPasswordHash() == null) {
      account.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
    }
    accountRepository.save(account);

    Set<GrantedAuthority> authorities = new HashSet<>(user.getAuthorities());
    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
    if ("PLAYER".equals(role)) authorities.add(new SimpleGrantedAuthority("ROLE_MEMBER"));
    return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "sub");
  }

  private String cognitoRole(OidcUser user) {
    Object claim = user.getClaims().get("cognito:groups");
    Collection<?> groups = claim instanceof Collection<?> collection ? collection : List.of();
    if (groups.stream().map(Object::toString).anyMatch("admin"::equals)) return "ADMIN";
    if (groups.stream().map(Object::toString).anyMatch("member"::equals)) return "PLAYER";
    throw new OAuth2AuthenticationException(
        new OAuth2Error("unauthorized_group"), "memberまたはadminグループが必要です。");
  }

  private String loginId(OidcUser user) {
    List<String> candidates = new ArrayList<>();
    candidates.add(user.getPreferredUsername());
    candidates.add(user.getEmail());
    candidates.add(user.getClaimAsString("cognito:username"));
    candidates.add(user.getSubject());
    return candidates.stream().filter(value -> value != null && !value.isBlank())
        .map(String::strip).map(value -> value.substring(0, Math.min(value.length(), 80)))
        .findFirst().orElseThrow();
  }
}
