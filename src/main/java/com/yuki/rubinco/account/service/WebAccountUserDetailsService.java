package com.yuki.rubinco.account.service;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebAccountUserDetailsService implements UserDetailsService {

  private final M_WebAccountRepository accountRepository;

  @Override
  public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
    M_WebAccount account = accountRepository.findByLoginId(loginId)
        .orElseThrow(() -> new UsernameNotFoundException("Web account was not found."));
    return User.withUsername(account.getLoginId())
        .password(account.getPasswordHash())
        .roles(account.getRole())
        .disabled(!account.isEnabled())
        .build();
  }
}
