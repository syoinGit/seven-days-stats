package com.yuki.rubinco.account.security;

import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@Profile("cognito")
@RequiredArgsConstructor
public class CognitoLogoutSuccessHandler implements LogoutSuccessHandler {
  private final PortalPlatformProperties properties;

  @Override
  public void onLogoutSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    String registration = properties.registrationForHost(request.getServerName());
    String clientId = properties.getCognito().clientId(registration);
    String returnUrl = properties.siteUrlForHost(request.getServerName());
    String authBaseUrl = properties.getAuthUrl().replaceFirst("/+$", "");
    String location = authBaseUrl + "/logout?client_id="
        + encode(clientId) + "&logout_uri=" + encode(returnUrl);
    response.sendRedirect(location);
  }

  private String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
