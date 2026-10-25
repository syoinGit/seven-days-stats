package com.yuki.rubinco.account.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

  @Bean
  @Profile("!cognito")
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      GameServerAccess gameServerAccess,
      AdminAccess adminAccess,
      GuestPrivacyFilter guestPrivacyFilter) throws Exception {
    http
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(
                "/login", "/guest-login", "/portal", "/css/**", "/img/**", "/js/**",
                "/favicon.ico", "/error")
            .permitAll()
            .requestMatchers(HttpMethod.TRACE, "/**").denyAll()
            .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
            .requestMatchers(HttpMethod.OPTIONS, "/**").denyAll()
            .requestMatchers("/guild/maintenance", "/guild/maintenance/**").access(adminAccess.requests())
            .requestMatchers(HttpMethod.GET, "/", "/guild", "/guild/visitors", "/guild/board", "/guild/characters", "/guild/characters/*", "/guild/log", "/guild/log/*", "/guild/pairs/*").permitAll()
            .requestMatchers("/octarine/maintenance/**", "/luminite/maintenance/**").access(adminAccess.requests())
            .requestMatchers("/maintenance/**", "/portal/maintenance/**").hasRole("ADMIN")
            .requestMatchers("/portal/profile").hasAnyRole("PLAYER", "MEMBER", "ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/games/*/events").hasRole("ADMIN")
            .requestMatchers(HttpMethod.PUT, "/api/games/*/events/*").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/games/*/start", "/api/games/*/stop",
                "/portal/actions/games/*/start", "/portal/actions/games/*/stop")
            .access(gameServerAccess.requests())
            // External IDs are masked only in HTML; raw API data requires a member account.
            .requestMatchers("/api/**").hasAnyRole("PLAYER", "ADMIN")
            .requestMatchers(
                HttpMethod.POST, "/players/*/status", "/posts", "/posts/*/like",
                "/posts/*/like.json", "/posts/*/react", "/posts/*/delete")
            .hasAnyRole("PLAYER", "ADMIN")
            .anyRequest().authenticated())
        .formLogin(form -> form
            .loginPage("/login")
            .defaultSuccessUrl("/portal", true)
            .permitAll())
        .logout(logout -> logout
            .logoutSuccessUrl("/")
            .permitAll())
        .headers(headers -> WebSecurityHeaders.configure(headers, null, null))
        .addFilterAfter(guestPrivacyFilter, AuthorizationFilter.class);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  FilterRegistrationBean<GuestPrivacyFilter> guestPrivacyFilterRegistration(
      GuestPrivacyFilter filter) {
    FilterRegistrationBean<GuestPrivacyFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
