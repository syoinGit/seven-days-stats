package com.yuki.rubinco.account.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import com.yuki.rubinco.portal.service.GameServerStartGateway;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:cognito_security_mvp;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.games.memory-budget-mib=16384",
    "COGNITO_ISSUER_URI=https://issuer.example.test/pool",
    "COGNITO_PORTAL_CLIENT_ID=portal-client",
    "COGNITO_WATCHPOINT_CLIENT_ID=watchpoint-client",
    "COGNITO_LUMINITE_CLIENT_ID=luminite-client"
})
@ActiveProfiles("cognito")
@AutoConfigureMockMvc
class CognitoSecurityMvpTests {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;
  @MockitoSpyBean private GameServerStartGateway startGateway;

  @Test
  void guildIsPublicButGenerationRequiresAdminSessionAndCsrf() throws Exception {
    mockMvc.perform(get("/guild")).andExpect(status().isOk());
    mockMvc.perform(get("/guild/log")).andExpect(status().isOk());
    mockMvc.perform(get("/guild/werewolf")).andExpect(status().isOk());
    mockMvc.perform(get("/guild/maintenance").with(user("member").roles("MEMBER")))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/guild/maintenance/generate").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/guild/maintenance/generate").with(user("admin").roles("ADMIN")).with(csrf()))
        .andExpect(status().is3xxRedirection());
  }

  @Test
  void sessionCookiesAndRejectedBearerTokensCannotAuthorizeTheJwtApi() throws Exception {
    var session = new MockHttpSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    mockMvc.perform(post("/api/games/7dtd/start").session(session))
        .andExpect(status().isUnauthorized());
    when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("test rejection"));
    mockMvc.perform(post("/api/games/7dtd/start")
            .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
        .andExpect(status().isUnauthorized());
    verify(startGateway, never()).requestStart(anyString(), anyString());
  }

  @Test
  void cognitoGuestTokensAndGuestSessionsCannotStartServers() throws Exception {
    for (String game : List.of("7dtd", "corekeeper", "minecraft", "terraria")) {
      String api = "/api/games/" + game + "/start";
      String bff = "/portal/actions/games/" + game + "/start";
      mockMvc.perform(post(api)).andExpect(status().isUnauthorized());
      mockMvc.perform(post(api).with(jwt())).andExpect(status().isForbidden());
      mockMvc.perform(post(bff).with(csrf())).andExpect(status().is3xxRedirection());
      for (String[] roles : List.of(new String[] {"VIEWER"}, new String[] {"VIEWER", "ADMIN"},
          new String[] {"VIEWER", "MEMBER"}, new String[] {"VIEWER", "PLAYER"})) {
        var authorities = Arrays.stream(roles)
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toArray(SimpleGrantedAuthority[]::new);
        mockMvc.perform(post(api).with(jwt().authorities(authorities)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(bff).with(user("guest").roles(roles)).with(csrf()))
            .andExpect(status().isForbidden());
      }
      mockMvc.perform(post(bff).with(user("member").roles("MEMBER")))
          .andExpect(status().isForbidden());
      mockMvc.perform(post(bff).with(user("admin").roles("ADMIN")).with(csrf().useInvalidToken()))
          .andExpect(status().isForbidden());
    }
    verify(startGateway, never()).requestStart(anyString(), anyString());
  }

  @Test
  void apiRejectsAnonymousAndUsersWithoutPortalGroups() throws Exception {
    mockMvc.perform(get("/api/games"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/api/games").with(jwt().jwt(token -> token.subject("no-group"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void memberCanReadAndStartButCannotWriteTimeline() throws Exception {
    var member = jwt().jwt(token -> token.subject("member-sub"))
        .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"));

    mockMvc.perform(get("/api/games").with(member))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("terraria")));

    mockMvc.perform(post("/api/games/7dtd/start").with(member))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("starting"));

    mockMvc.perform(post("/api/games/terraria/events")
            .with(member)
            .contentType(MediaType.APPLICATION_JSON)
            .content(eventJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanWriteTimelineWithoutSessionOrCsrfCookie() throws Exception {
    mockMvc.perform(post("/api/games/7dtd/events")
            .with(jwt().jwt(token -> token.subject("admin-sub"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(eventJson()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.gameId").value("7dtd"))
        .andExpect(jsonPath("$.creatorSub").value("admin-sub"));
  }

  @Test
  void corsAllowsOnlyConfiguredPortalOrigins() throws Exception {
    mockMvc.perform(options("/api/games")
            .header(HttpHeaders.ORIGIN, "https://portal.rubinco.link")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
            "https://portal.rubinco.link"));

    mockMvc.perform(options("/api/games")
            .header(HttpHeaders.ORIGIN, "https://attacker.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  void protectedSiteRedirectsToPortalAndModalUsesPortalClient() throws Exception {
    mockMvc.perform(get("/luminite").header(HttpHeaders.HOST, "luminite.rubinco.link"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string(
            HttpHeaders.LOCATION, "https://portal.rubinco.link/portal?login=true"));

    mockMvc.perform(get("/portal?login&error").header(HttpHeaders.HOST, "portal.rubinco.link"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string(
            "Content-Security-Policy", containsString("https://auth.rubinco.link")))
        .andExpect(content().string(containsString("/oauth2/authorization/portal")))
        .andExpect(content().string(containsString("共通ログインに失敗しました")));
  }

  @Test
  void gameLoginAlwaysOpensThePortalAndMemberLinksEstablishDestinationSessions() throws Exception {
    for (String host : new String[] {"watchpoint.rubinco.link", "luminite.rubinco.link"}) {
      mockMvc.perform(get("/login?error").header(HttpHeaders.HOST, host))
          .andExpect(status().is3xxRedirection())
          .andExpect(header().string(HttpHeaders.LOCATION,
              "https://portal.rubinco.link/portal?login=true&error"));
      mockMvc.perform(get("/portal?login").header(HttpHeaders.HOST, host))
          .andExpect(header().string(HttpHeaders.LOCATION,
              "https://portal.rubinco.link/portal?login=true"));
    }
    var properties = new PortalPlatformProperties();
    properties.getCognito().setEnabled(true);
    org.assertj.core.api.Assertions.assertThat(properties.siteEntryUrl(properties.getWatchpointUrl()))
        .isEqualTo("https://watchpoint.rubinco.link/oauth2/authorization/watchpoint");
    org.assertj.core.api.Assertions.assertThat(properties.siteEntryUrl(properties.getLuminiteUrl()))
        .isEqualTo("https://luminite.rubinco.link/oauth2/authorization/luminite");
  }

  @Test
  void portalMaintenanceRequiresAdminAndRedirectsToThePortalHost() throws Exception {
    var member = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("member").roles("MEMBER");
    var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN");
    mockMvc.perform(get("/portal/maintenance/accounts").with(member))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/maintenance/accounts").header(HttpHeaders.HOST, "watchpoint.rubinco.link").with(admin))
        .andExpect(header().string(HttpHeaders.LOCATION, "https://portal.rubinco.link/portal/maintenance/accounts"));
    mockMvc.perform(get("/portal/maintenance/accounts").header(HttpHeaders.HOST, "portal.rubinco.link").with(admin))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("初めてログインすると")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"password\""))));
  }

  private String eventJson() {
    return """
        {"eventType":"PROGRESS","title":"Shared event","body":"Shared body",
         "progress":30,"stageName":"Stage 2","metadata":{}}
        """;
  }
}
