package com.yuki.rubinco.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

class CognitoLoginSuccessHandlerTests {
  @ParameterizedTest
  @CsvSource({"portal,/portal", "watchpoint,/dashboard", "luminite,/luminite"})
  void returnsToTheAuthenticatedSiteAndDiscardsOldLoginRequests(String registration, String target)
      throws Exception {
    var request = new MockHttpServletRequest("GET", "/login");
    var response = new MockHttpServletResponse();
    var cache = new HttpSessionRequestCache();
    cache.saveRequest(request, response);
    request.getSession().setAttribute("SPRING_SECURITY_LAST_EXCEPTION", new RuntimeException());
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
    var user = new DefaultOAuth2User(authorities, Map.of("sub", "member"), "sub");
    var authentication = new OAuth2AuthenticationToken(user, authorities, registration);

    new CognitoLoginSuccessHandler().onAuthenticationSuccess(request, response, authentication);

    assertThat(response.getRedirectedUrl()).isEqualTo(target);
    assertThat(cache.getRequest(request, response)).isNull();
    assertThat(request.getSession().getAttribute("SPRING_SECURITY_LAST_EXCEPTION")).isNull();
  }
}
