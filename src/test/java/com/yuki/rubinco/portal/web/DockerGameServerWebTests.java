package com.yuki.rubinco.portal.web;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuki.rubinco.portal.repository.M_GamePortalStateRepository;
import com.yuki.rubinco.portal.service.DockerCommandRunner;
import com.yuki.rubinco.portal.service.GameStartService;
import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import java.util.List;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:docker_game_web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "GAME_SERVER_START_PROVIDER=docker"
})
@AutoConfigureMockMvc
class DockerGameServerWebTests {
  @Autowired MockMvc mvc;
  @Autowired M_GamePortalStateRepository states;
  @Autowired M_WebAccountRepository accounts;
  @Autowired GameStartService startService;
  @MockitoBean DockerCommandRunner runner;

  @Test
  void guestAndMixedGuestRolesCannotStartAnyGameThroughEitherRoute() throws Exception {
    for (String[] roles : List.of(new String[] {"VIEWER"}, new String[] {"VIEWER", "ADMIN"},
        new String[] {"VIEWER", "MEMBER"}, new String[] {"VIEWER", "PLAYER"}, new String[] {"UNKNOWN"})) {
      for (String game : List.of("7dtd", "corekeeper", "minecraft", "terraria")) {
        for (String prefix : List.of("/portal/actions/games/", "/api/games/")) {
          mvc.perform(post(prefix + game + "/start").with(user("guest").roles(roles)).with(csrf()))
              .andExpect(status().isForbidden());
        }
      }
    }
    verifyNoInteractions(runner);
  }

  @Test
  void anonymousAndMissingOrInvalidCsrfRequestsCannotInvokeDocker() throws Exception {
    for (String prefix : List.of("/portal/actions/games/", "/api/games/")) {
      for (String game : List.of("7dtd", "corekeeper", "minecraft")) {
        String path = prefix + game + "/start";
        mvc.perform(post(path).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post(path).with(user("member").roles("PLAYER")))
            .andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf().useInvalidToken()))
            .andExpect(status().isForbidden());
      }
    }
    verifyNoInteractions(runner);
  }

  @Test
  @WithMockUser(username = "guest", roles = {"VIEWER", "ADMIN"})
  void serviceItselfRejectsGuestsBeforeTouchingDockerOrState() {
    String previous = states.findById("7dtd").orElseThrow().getServerStatus();
    assertThatThrownBy(() -> startService.requestStart("7dtd", "forged-admin-name"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(states.findById("7dtd").orElseThrow().getServerStatus()).isEqualTo(previous);
    verifyNoInteractions(runner);
  }

  @Test
  void serviceItselfRejectsCallsWithoutAuthentication() {
    assertThatThrownBy(() -> startService.requestStart("7dtd", "forged-admin-name"))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(runner);
  }

  @Test
  void realGuestLoginSessionHidesControlsAndCannotStartContainers() throws Exception {
    if (accounts.findByLoginId("guest").isEmpty()) {
      var account = new M_WebAccount();
      account.setLoginId("guest");
      account.setRole("VIEWER");
      account.setPasswordHash("unused-by-guest-login");
      accounts.save(account);
    }
    var session = new MockHttpSession();
    mvc.perform(post("/guest-login").session(session).with(csrf()))
        .andExpect(status().is3xxRedirection());
    for (String prefix : List.of("/portal/actions/games/", "/api/games/")) {
      mvc.perform(post(prefix + "7dtd/start").session(session).with(csrf()))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(runner);
    // Reading the public portal may inspect status; it must not expose an operation control.
    mvc.perform(get("/portal").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("data-start-server="))));
    mvc.perform(get("/portal").with(user("guest").roles("VIEWER", "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("data-start-server="))));
    verify(runner, never()).run("start", "7dtd");
  }

  @Test
  void memberStartsStoppedDockerDespiteStaleStoredStatusAndKeepsTerrariaAutomatic() throws Exception {
    var state = states.findById("minecraft").orElseThrow();
    state.setServerStatus("online");
    states.save(state);
    when(runner.run("status", "minecraft"))
        .thenReturn("{\"Status\":\"exited\"}");

    mvc.perform(post("/portal/actions/games/minecraft/start").with(user("member").roles("PLAYER")).with(csrf()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
    verify(runner).run("start", "minecraft");

    mvc.perform(post("/portal/actions/games/terraria/start").with(user("member").roles("PLAYER")).with(csrf()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false));
    verify(runner, never()).run("start", "terraria");

    var corekeeper = states.findById("corekeeper").orElseThrow();
    corekeeper.setServerStatus("maintenance");
    states.save(corekeeper);
    when(runner.run("statuses"))
        .thenReturn("7dtd {\"Status\":\"exited\"}\nterraria {\"Status\":\"running\"}\n"
            + "corekeeper {\"Status\":\"exited\"}\nminecraft {\"Status\":\"running\"}");
    mvc.perform(get("/portal/actions/games/status").with(user("member").roles("PLAYER")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.7dtd.code").value("offline"))
        .andExpect(jsonPath("$.terraria.code").value("online"))
        .andExpect(jsonPath("$.corekeeper.code").value("maintenance"))
        .andExpect(jsonPath("$.minecraft.code").value("online"));
  }
}
