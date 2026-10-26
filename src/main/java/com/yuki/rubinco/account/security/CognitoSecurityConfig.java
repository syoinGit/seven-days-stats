package com.yuki.rubinco.account.security;

import com.yuki.rubinco.account.service.CognitoOidcUserService;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@Profile("cognito")
public class CognitoSecurityConfig {

  @Bean
  @Order(1)
  SecurityFilterChain cognitoApiSecurityFilterChain(
      HttpSecurity http,
      GameServerAccess gameServerAccess,
      JwtDecoder jwtDecoder,
      CorsConfigurationSource corsConfigurationSource) throws Exception {
    http
        .securityMatcher("/api/**")
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/games/*/events").hasRole("ADMIN")
            .requestMatchers(HttpMethod.PUT, "/api/games/*/events/*").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/games/*/start", "/api/games/*/stop")
            .access(gameServerAccess.requests())
            .anyRequest().hasAnyRole("MEMBER", "ADMIN"))
        .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt
            .decoder(jwtDecoder)
            .jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .headers(headers -> headers
            .contentTypeOptions(contentTypeOptions -> {})
            .frameOptions(frameOptions -> frameOptions.deny())
            .referrerPolicy(referrer -> referrer.policy(
                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain cognitoWebSecurityFilterChain(
      HttpSecurity http,
      GameServerAccess gameServerAccess,
      AdminAccess adminAccess,
      GuestPrivacyFilter guestPrivacyFilter,
      CognitoOidcUserService oidcUserService,
      CognitoAuthenticationEntryPoint authenticationEntryPoint,
      CognitoLogoutSuccessHandler logoutSuccessHandler,
      CognitoLoginSuccessHandler loginSuccessHandler,
      PortalPlatformProperties properties) throws Exception {
    http
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(
                "/login", "/portal", "/oauth2/**", "/login/oauth2/**", "/css/**",
                "/img/**", "/js/**", "/favicon.ico", "/error")
            .permitAll()
            .requestMatchers(HttpMethod.TRACE, "/**").denyAll()
            .requestMatchers(HttpMethod.OPTIONS, "/**").denyAll()
            .requestMatchers("/guild/maintenance", "/guild/maintenance/**").access(adminAccess.requests())
            .requestMatchers(HttpMethod.GET, "/", "/guild", "/guild/visitors", "/guild/board", "/guild/characters", "/guild/characters/*", "/guild/log", "/guild/log/*", "/guild/pairs/*", "/guild/dungeon", "/guild/dungeon/*", "/guild/signboard").permitAll()
            .requestMatchers("/octarine/maintenance/**", "/luminite/maintenance/**").access(adminAccess.requests())
            .requestMatchers("/maintenance/**", "/portal/maintenance/**").hasRole("ADMIN")
            .requestMatchers("/portal/profile").hasAnyRole("PLAYER", "MEMBER", "ADMIN")
            .requestMatchers(HttpMethod.POST,
                "/portal/actions/games/*/start", "/portal/actions/games/*/stop")
            .access(gameServerAccess.requests())
            .requestMatchers(
                HttpMethod.POST, "/players/*/status", "/posts", "/posts/*/like",
                "/posts/*/like.json", "/posts/*/react", "/posts/*/delete")
            .hasAnyRole("MEMBER", "PLAYER", "ADMIN")
            .anyRequest().authenticated())
        .oauth2Login(oauth -> oauth
            .loginPage("/login")
            .successHandler(loginSuccessHandler)
            .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)))
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(authenticationEntryPoint))
        .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler).permitAll())
        .headers(headers -> WebSecurityHeaders.configure(
            headers, properties.getAuthUrl(), properties.getApiUrl()))
        .addFilterAfter(guestPrivacyFilter, AuthorizationFilter.class);
    return http.build();
  }

  @Bean
  JwtDecoder cognitoJwtDecoder(
      PortalPlatformProperties properties,
      Environment environment) {
    String issuer = environment.getRequiredProperty(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri");
    JwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    if (!(decoder instanceof NimbusJwtDecoder nimbus)) {
      throw new IllegalStateException(
          "Cognito JWT decoder does not support the required custom claim validation");
    }
    OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
    nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        defaults, cognitoClaimsValidator(properties)));
    return nimbus;
  }

  OAuth2TokenValidator<Jwt> cognitoClaimsValidator(PortalPlatformProperties properties) {
    List<String> configuredClients = properties.getCognito().dedicatedClientIds();
    Set<String> acceptedClients = new HashSet<>(configuredClients);
    if (configuredClients.stream().anyMatch(value ->
        value == null || value.isBlank() || !value.equals(value.strip()))
        || acceptedClients.size() != configuredClients.size()) {
      throw new IllegalStateException("Cognito app client IDs must be non-blank and unique");
    }
    return token -> {
      String tokenUse = token.getClaimAsString("token_use");
      String clientId = token.getClaimAsString("client_id");
      if (!"access".equals(tokenUse) || !acceptedClients.contains(clientId)) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
            "invalid_token", "Cognito access token/client_id is not accepted", null));
      }
      return OAuth2TokenValidatorResult.success();
    };
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(PortalPlatformProperties properties) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.getAllowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-TOKEN"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }

  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName("sub");
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Set<GrantedAuthority> authorities = new HashSet<>();
      List<String> groups = jwt.getClaimAsStringList("cognito:groups");
      if (groups != null && groups.contains("admin")) {
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
      }
      if (groups != null && groups.contains("member")) {
        authorities.add(new SimpleGrantedAuthority("ROLE_MEMBER"));
        authorities.add(new SimpleGrantedAuthority("ROLE_PLAYER"));
      }
      return authorities;
    });
    return converter;
  }
}
