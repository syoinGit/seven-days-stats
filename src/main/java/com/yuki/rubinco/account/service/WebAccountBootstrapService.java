package com.yuki.rubinco.account.service;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebAccountBootstrapService {

  private final M_WebAccountRepository accountRepository;
  private final PasswordEncoder passwordEncoder;

  @Value("${app.auth.bootstrap.login:}")
  private String bootstrapLogin;

  @Value("${app.auth.bootstrap.password:}")
  private String bootstrapPassword;

  @PostConstruct
  public void createBootstrapAdminIfConfigured() {
    createGuestAccountIfMissing();
    if (bootstrapLogin == null || bootstrapLogin.isBlank()
        || bootstrapPassword == null || bootstrapPassword.isBlank()
        || accountRepository.existsByLoginId(bootstrapLogin.strip())) {
      return;
    }
    M_WebAccount account = new M_WebAccount();
    account.setLoginId(bootstrapLogin.strip());
    account.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
    account.setRole("ADMIN");
    account.setEnabled(true);
    accountRepository.save(account);
  }

  private void createGuestAccountIfMissing() {
    if (accountRepository.existsByLoginId("guest")) {
      return;
    }
    M_WebAccount guest = new M_WebAccount();
    guest.setLoginId("guest");
    guest.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
    guest.setRole("VIEWER");
    guest.setEnabled(true);
    accountRepository.save(guest);
  }
}
