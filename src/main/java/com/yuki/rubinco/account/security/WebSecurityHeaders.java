package com.yuki.rubinco.account.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/** Browser policy shared by local and Cognito login; OAuth adds its explicit trusted origins. */
final class WebSecurityHeaders {

  private WebSecurityHeaders() {
  }

  static void configure(HeadersConfigurer<HttpSecurity> headers, String authOrigin, String apiOrigin) {
    String formSources = "'self'" + (authOrigin == null ? "" : " " + authOrigin);
    String connectSources = "'self'" + (apiOrigin == null ? "" : " " + apiOrigin);
    headers
        .contentTypeOptions(contentTypeOptions -> {})
        .frameOptions(frameOptions -> frameOptions.deny())
        .referrerPolicy(referrer -> referrer.policy(
            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
        .addHeaderWriter(new StaticHeadersWriter(
            "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin"))
        .contentSecurityPolicy(csp -> csp.policyDirectives(
            "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; "
                + "frame-src 'none'; form-action " + formSources + "; "
                + "img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self'; connect-src " + connectSources));
  }
}
