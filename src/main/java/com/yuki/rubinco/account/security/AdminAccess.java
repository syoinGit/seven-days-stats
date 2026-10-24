package com.yuki.rubinco.account.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/** Administrator check shared by routes, controllers and services. Guest roles always win. */
@Component("adminAccess")
public class AdminAccess {
  public boolean isAdmin(Authentication authentication) {
    return authentication != null && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && !GuestPrivacyService.isGuest(authentication)
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
  }

  public boolean currentUserIsAdmin() {
    return isAdmin(SecurityContextHolder.getContext().getAuthentication());
  }

  public AuthorizationManager<RequestAuthorizationContext> requests() {
    return (authentication, context) -> new AuthorizationDecision(isAdmin(authentication.get()));
  }
}
