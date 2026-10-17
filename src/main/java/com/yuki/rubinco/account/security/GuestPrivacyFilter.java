package com.yuki.rubinco.account.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@RequiredArgsConstructor
public class GuestPrivacyFilter extends OncePerRequestFilter {

  private final GuestPrivacyService privacyService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.isAuthenticated()
        && !"anonymousUser".equals(authentication.getName())) {
      response.setHeader("Cache-Control", "no-store, max-age=0");
      response.setHeader("Pragma", "no-cache");
    }
    if (!GuestPrivacyService.isGuest(authentication)) {
      filterChain.doFilter(request, response);
      return;
    }

    ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
    filterChain.doFilter(request, wrapped);
    if (!isHtml(wrapped)) {
      wrapped.copyBodyToResponse();
      return;
    }

    Charset charset = responseCharset(wrapped);
    String html = new String(wrapped.getContentAsByteArray(), charset);
    byte[] protectedHtml = privacyService.protectHtml(html).getBytes(charset);
    wrapped.resetBuffer();
    wrapped.setContentLength(protectedHtml.length);
    wrapped.getOutputStream().write(protectedHtml);
    wrapped.copyBodyToResponse();
  }

  private static boolean isHtml(HttpServletResponse response) {
    return response.getContentType() != null
        && response.getContentType().toLowerCase().startsWith("text/html");
  }

  private static Charset responseCharset(HttpServletResponse response) {
    try {
      return Charset.forName(response.getCharacterEncoding());
    } catch (RuntimeException ignored) {
      return StandardCharsets.UTF_8;
    }
  }
}
