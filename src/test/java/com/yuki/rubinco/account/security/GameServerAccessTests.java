package com.yuki.rubinco.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class GameServerAccessTests {
  private final GameServerAccess access = new GameServerAccess();

  @Test
  void deniesMissingAnonymousUnauthenticatedAndUnrecognizedPrincipals() {
    assertThat(access.canStart(null)).isFalse();
    assertThat(access.canStart(new AnonymousAuthenticationToken("key", "anonymousUser",
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))).isFalse();
    var unauthenticated = authentication("ADMIN");
    unauthenticated.setAuthenticated(false);
    assertThat(access.canStart(unauthenticated)).isFalse();
    assertThat(access.canStart(authentication("VIEWER"))).isFalse();
    assertThat(access.canStart(authentication("UNKNOWN"))).isFalse();
    assertThat(access.canStart(authentication())).isFalse();
  }

  @Test
  void guestRoleOverridesEveryOtherwisePermittedRole() {
    for (String role : List.of("ADMIN", "MEMBER", "PLAYER")) {
      assertThat(access.canStart(authentication("VIEWER", role))).isFalse();
    }
  }

  @Test
  void allowsOnlyAuthenticatedMembersAndAdministrators() {
    for (String role : List.of("ADMIN", "MEMBER", "PLAYER")) {
      assertThat(access.canStart(authentication(role))).isTrue();
    }
  }

  private UsernamePasswordAuthenticationToken authentication(String... roles) {
    return UsernamePasswordAuthenticationToken.authenticated("test-user", null,
        Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
  }
}
