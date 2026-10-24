package com.yuki.rubinco.account.security;

import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/** Shared by HTTP authorization, service authorization, and the portal controls. */
@Component("gameServerAccess")
public class GameServerAccess {
  private static final Set<String> START_ROLES = Set.of("ROLE_ADMIN", "ROLE_MEMBER", "ROLE_PLAYER");

  public boolean canStart(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken
        || GuestPrivacyService.isGuest(authentication)) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> START_ROLES.contains(authority.getAuthority()));
  }

  public boolean currentUserCanStart() {
    return canStart(SecurityContextHolder.getContext().getAuthentication());
  }

  public AuthorizationManager<RequestAuthorizationContext> requests() {
    return (authentication, context) -> new AuthorizationDecision(canStart(authentication.get()));
  }
}
