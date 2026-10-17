package com.yuki.rubinco.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class CognitoPkceConfigurationTests {

  @Test
  void publicAuthorizationCodeClientAddsS256PkceParameters() {
    ClientRegistration registration = ClientRegistration.withRegistrationId("portal")
        .clientId("portal-client")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://portal.rubinco.link/login/oauth2/code/portal")
        .scope("openid", "profile", "email")
        .authorizationUri("https://auth.rubinco.link/oauth2/authorize")
        .tokenUri("https://auth.rubinco.link/oauth2/token")
        .userInfoUri("https://auth.rubinco.link/oauth2/userInfo")
        .userNameAttributeName("sub")
        .jwkSetUri("https://issuer.example.test/.well-known/jwks.json")
        .clientName("Portal")
        .build();
    var repository = new InMemoryClientRegistrationRepository(registration);
    var resolver = new DefaultOAuth2AuthorizationRequestResolver(
        repository, "/oauth2/authorization");
    var request = new MockHttpServletRequest("GET", "/oauth2/authorization/portal");

    var authorizationRequest = resolver.resolve(request);

    assertThat(authorizationRequest).isNotNull();
    assertThat(authorizationRequest.getAdditionalParameters())
        .containsEntry("code_challenge_method", "S256")
        .containsKey("code_challenge");
    assertThat(authorizationRequest.getAttributes()).containsKey("code_verifier");
  }
}
