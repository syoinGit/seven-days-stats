package com.yuki.sevendays_states.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
public class WebSecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      GuestPrivacyFilter guestPrivacyFilter) throws Exception {
    http
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(
                "/login", "/guest-login", "/css/**", "/img/**", "/js/**", "/favicon.ico", "/error")
            .permitAll()
            .requestMatchers(HttpMethod.TRACE, "/**").denyAll()
            .requestMatchers(HttpMethod.OPTIONS, "/**").denyAll()
            .requestMatchers(HttpMethod.GET, "/").permitAll()
            .requestMatchers("/maintenance/**").hasRole("ADMIN")
            .requestMatchers(
                HttpMethod.POST, "/players/*/status", "/posts", "/posts/*/like",
                "/posts/*/like.json", "/posts/*/react", "/posts/*/delete")
            .hasAnyRole("PLAYER", "ADMIN")
            .anyRequest().authenticated())
        .formLogin(form -> form
            .loginPage("/login")
            .defaultSuccessUrl("/dashboard", true)
            .permitAll())
        .logout(logout -> logout
            .logoutSuccessUrl("/")
            .permitAll())
        .headers(headers -> headers
            .contentTypeOptions(contentTypeOptions -> {})
            .frameOptions(frameOptions -> frameOptions.deny())
            .referrerPolicy(referrer -> referrer.policy(
                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(31536000))
            .addHeaderWriter(new StaticHeadersWriter(
                "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
            .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
            .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin"))
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; object-src 'none'; base-uri 'self'; "
                    + "frame-ancestors 'none'; frame-src 'none'; form-action 'self'; "
                    + "img-src 'self' data: https:; "
                    + "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'")))
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
