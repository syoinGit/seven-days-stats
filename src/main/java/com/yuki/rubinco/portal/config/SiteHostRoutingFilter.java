package com.yuki.rubinco.portal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class SiteHostRoutingFilter extends OncePerRequestFilter {
  private final PortalPlatformProperties properties;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if ("GET".equals(request.getMethod()) && "/".equals(request.getRequestURI())) {
      String registration = properties.registrationForHost(request.getServerName());
      if ("portal".equals(registration)
          || properties.getDomain().equalsIgnoreCase(request.getServerName())
          || "localhost".equalsIgnoreCase(request.getServerName())
          || "127.0.0.1".equals(request.getServerName())
          || "[::1]".equals(request.getServerName())
          || "::1".equals(request.getServerName())) {
        response.sendRedirect("/portal");
        return;
      }
      if ("luminite".equals(registration)) {
        response.sendRedirect("/luminite");
        return;
      }
      if ("octarine".equals(registration)) {
        response.sendRedirect("/octarine");
        return;
      }
    }
    filterChain.doFilter(request, response);
  }
}
