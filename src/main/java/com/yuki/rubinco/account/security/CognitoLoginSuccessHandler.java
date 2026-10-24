package com.yuki.rubinco.account.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Component;

@Component
@Profile("cognito")
public class CognitoLoginSuccessHandler implements AuthenticationSuccessHandler {
  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException {
    new HttpSessionRequestCache().removeRequest(request, response);
    request.getSession().removeAttribute("SPRING_SECURITY_LAST_EXCEPTION");
    String registration = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
    response.sendRedirect(switch (registration) {
      case "watchpoint" -> "/dashboard";
      case "luminite" -> "/luminite";
      case "octarine" -> "/octarine";
      default -> "/portal";
    });
  }
}
