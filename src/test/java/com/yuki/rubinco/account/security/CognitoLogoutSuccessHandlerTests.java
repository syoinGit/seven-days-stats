package com.yuki.rubinco.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CognitoLogoutSuccessHandlerTests {

  @Test
  void usesTheCurrentSitesClientAndConfiguredReturnUrl() throws Exception {
    PortalPlatformProperties properties = new PortalPlatformProperties();
    properties.setAuthUrl("https://auth.rubinco.link/");
    properties.getCognito().setLuminiteClientId("luminite-client");
    CognitoLogoutSuccessHandler handler = new CognitoLogoutSuccessHandler(properties);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setServerName("luminite.rubinco.link");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onLogoutSuccess(request, response, null);

    assertThat(response.getRedirectedUrl()).isEqualTo(
        "https://auth.rubinco.link/logout?client_id=luminite-client"
            + "&logout_uri=https%3A%2F%2Fluminite.rubinco.link");
  }
}
