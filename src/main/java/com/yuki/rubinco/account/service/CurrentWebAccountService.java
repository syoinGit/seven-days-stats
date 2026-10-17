package com.yuki.rubinco.account.service;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentWebAccountService {

  private final M_WebAccountRepository accountRepository;

  public Optional<M_WebAccount> current(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getName())) {
      return Optional.empty();
    }
    Optional<M_WebAccount> account = authentication.getPrincipal() instanceof OidcUser oidc
        ? accountRepository.findByCognitoSub(oidc.getSubject())
        : accountRepository.findByLoginId(authentication.getName());
    return account
        .filter(M_WebAccount::isEnabled);
  }
}
