package com.yuki.rubinco.account.security;

import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
@Profile("cognito")
@RequiredArgsConstructor
public class CognitoAuthenticationEntryPoint implements AuthenticationEntryPoint {
  private final PortalPlatformProperties properties;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException) throws IOException {
    response.sendRedirect(properties.portalLoginUrl());
  }
}
