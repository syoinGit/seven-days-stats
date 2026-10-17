package com.yuki.rubinco.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@ExtendWith(MockitoExtension.class)
class CognitoOidcUserServiceTests {
  @Mock private M_WebAccountRepository accountRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private CognitoOidcUserService service;

  @BeforeEach
  void setUp() {
    service = new CognitoOidcUserService(accountRepository, passwordEncoder);
  }

  @Test
  void invitedMemberCreatesAPlayerCompatibleAccount() {
    when(accountRepository.findByCognitoSub("member-sub")).thenReturn(Optional.empty());
    when(accountRepository.findByLoginId("watchpoint-friend")).thenReturn(Optional.empty());
    when(passwordEncoder.encode(any())).thenReturn("encoded-random-placeholder");

    OidcUser result = service.synchronize(user(
        "member-sub", "watchpoint-friend", "friend@example.com", "member"));

    ArgumentCaptor<M_WebAccount> account = ArgumentCaptor.forClass(M_WebAccount.class);
    verify(accountRepository).save(account.capture());
    assertThat(account.getValue()).satisfies(saved -> {
      assertThat(saved.getLoginId()).isEqualTo("watchpoint-friend");
      assertThat(saved.getCognitoSub()).isEqualTo("member-sub");
      assertThat(saved.getRole()).isEqualTo("PLAYER");
      assertThat(saved.isEnabled()).isTrue();
      assertThat(saved.getPasswordHash()).isEqualTo("encoded-random-placeholder");
    });
    assertThat(result.getAuthorities()).extracting(Object::toString)
        .contains("ROLE_MEMBER", "ROLE_PLAYER");
  }

  @Test
  void refusesToRelinkAnExistingLoginToAnotherSubject() {
    M_WebAccount existing = account("watchpoint-friend", "different-sub", true);
    when(accountRepository.findByCognitoSub("member-sub")).thenReturn(Optional.empty());
    when(accountRepository.findByLoginId("watchpoint-friend")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.synchronize(user(
        "member-sub", "watchpoint-friend", "friend@example.com", "member")))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("別のSSOユーザー");
  }

  @Test
  void refusesAnAccountDisabledAfterItWasLinked() {
    M_WebAccount disabled = account("watchpoint-friend", "member-sub", false);
    disabled.setId(10L);
    when(accountRepository.findByCognitoSub("member-sub")).thenReturn(Optional.of(disabled));

    assertThatThrownBy(() -> service.synchronize(user(
        "member-sub", "changed-name", "friend@example.com", "member")))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("無効");
  }

  private M_WebAccount account(String loginId, String subject, boolean enabled) {
    M_WebAccount account = new M_WebAccount();
    account.setLoginId(loginId);
    account.setCognitoSub(subject);
    account.setPasswordHash("existing-hash");
    account.setEnabled(enabled);
    return account;
  }

  private OidcUser user(String subject, String preferredUsername, String email, String group) {
    Instant now = Instant.now();
    Map<String, Object> claims = Map.of(
        "sub", subject,
        "preferred_username", preferredUsername,
        "email", email,
        "cognito:groups", List.of(group));
    OidcIdToken idToken = new OidcIdToken(
        "id-token", now.minusSeconds(5), now.plusSeconds(300), claims);
    OidcUserInfo userInfo = new OidcUserInfo(claims);
    return new DefaultOidcUser(List.of(), idToken, userInfo, "sub");
  }
}
