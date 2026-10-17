package com.yuki.rubinco.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoJwtClaimsValidatorTests {
  private CognitoSecurityConfig securityConfig;
  private PortalPlatformProperties properties;

  @BeforeEach
  void setUp() {
    securityConfig = new CognitoSecurityConfig();
    properties = new PortalPlatformProperties();
    properties.getCognito().setPortalClientId("portal-client");
    properties.getCognito().setWatchpointClientId("watchpoint-client");
    properties.getCognito().setLuminiteClientId("luminite-client");
  }

  @Test
  void acceptsOnlyAccessTokensFromKnownSiteClients() {
    assertThat(securityConfig.cognitoClaimsValidator(properties)
        .validate(jwt("access", "portal-client")).hasErrors()).isFalse();
    assertThat(securityConfig.cognitoClaimsValidator(properties)
        .validate(jwt("access", "watchpoint-client")).hasErrors()).isFalse();
    assertThat(securityConfig.cognitoClaimsValidator(properties)
        .validate(jwt("access", "luminite-client")).hasErrors()).isFalse();
  }

  @Test
  void rejectsIdTokensAndUnknownClients() {
    assertThat(securityConfig.cognitoClaimsValidator(properties)
        .validate(jwt("id", "portal-client")).hasErrors()).isTrue();
    assertThat(securityConfig.cognitoClaimsValidator(properties)
        .validate(jwt("access", "attacker-client")).hasErrors()).isTrue();
  }

  @Test
  void rejectsAmbiguousClientConfigurationBeforeValidatingTokens() {
    properties.getCognito().setLuminiteClientId("portal-client");

    assertThatThrownBy(() -> securityConfig.cognitoClaimsValidator(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-blank and unique");
  }

  @Test
  void mapsCognitoGroupsToLeastPrivilegeApiRoles() {
    Jwt member = jwt("access", "portal-client", List.of("member"));
    Jwt admin = jwt("access", "portal-client", List.of("admin"));
    Jwt ungrouped = jwt("access", "portal-client", List.of());

    assertThat(applicationRoles(member))
        .containsExactlyInAnyOrder("ROLE_MEMBER", "ROLE_PLAYER");
    assertThat(applicationRoles(admin))
        .containsExactly("ROLE_ADMIN");
    assertThat(applicationRoles(ungrouped))
        .isEmpty();
  }

  private List<String> applicationRoles(Jwt jwt) {
    return securityConfig.jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(authority -> authority.startsWith("ROLE_"))
        .toList();
  }

  private Jwt jwt(String tokenUse, String clientId) {
    return jwt(tokenUse, clientId, List.of());
  }

  private Jwt jwt(String tokenUse, String clientId, List<String> groups) {
    Instant now = Instant.now();
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject("subject")
        .issuedAt(now.minusSeconds(30))
        .expiresAt(now.plusSeconds(300))
        .claim("token_use", tokenUse)
        .claim("client_id", clientId)
        .claim("cognito:groups", groups)
        .build();
  }
}
