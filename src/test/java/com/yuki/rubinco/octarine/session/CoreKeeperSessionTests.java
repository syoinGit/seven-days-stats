package com.yuki.rubinco.octarine.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:corekeeper_sessions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.corekeeper.log-stream-enabled=true",
    "app.corekeeper.queue-poll-interval=1h"
})
@AutoConfigureMockMvc
class CoreKeeperSessionTests {
  static final String ALICE = "76561198000000001";
  static final String BOB = "76561198000000002";

  // Lines are fed directly; the real stream would call sudo.
  @MockitoBean CoreKeeperLogStreamingService streaming;
  @Autowired CoreKeeperSessionStore store;
  @Autowired CoreKeeperSessionQueryService sessions;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  long sevenDaysPlayer;
  long memberAccount;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM T_CK_SESSION");
    jdbc.update("DELETE FROM M_CK_PLAYER");
    jdbc.update("DELETE FROM M_WEB_ACCOUNT WHERE login_id IN ('ck-member', 'ck-admin')");
    jdbc.update("DELETE FROM M_PLAYER WHERE player_key = 'EOS:ck-alice'");
    // A 7DTD player: EOS first, Steam as the native id, the way 7DTD writes it.
    jdbc.update("INSERT INTO M_PLAYER (player_key, platform, user_id, native_platform, native_user_id, player_name) "
        + "VALUES ('EOS:ck-alice', 'EOS', 'ck-alice', 'Steam', ?, 'Alice7')", ALICE);
    sevenDaysPlayer = jdbc.queryForObject("SELECT id FROM M_PLAYER WHERE player_key = 'EOS:ck-alice'", Long.class);
    jdbc.update("INSERT INTO M_WEB_ACCOUNT (login_id, password_hash, role, player_id, enabled, created_at, updated_at) "
        + "VALUES ('ck-member', 'unused', 'PLAYER', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", sevenDaysPlayer);
    jdbc.update("INSERT INTO M_WEB_ACCOUNT (login_id, password_hash, role, enabled, created_at, updated_at) "
        + "VALUES ('ck-admin', 'unused', 'ADMIN', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    memberAccount = jdbc.queryForObject("SELECT id FROM M_WEB_ACCOUNT WHERE login_id = 'ck-member'", Long.class);
  }

  void feed(List<String> lines) {
    var parser = new CoreKeeperLogParser();
    lines.forEach(line -> parser.accept(line).ifPresent(store::apply));
  }

  List<String> log() {
    List<String> lines = new ArrayList<>(CoreKeeperLogParserTests.connection(ALICE, "111", "Alice",
        "2026-10-01T10:00:00Z", "2026-10-01T11:30:00Z"));
    // Bob is still connected: no disconnect line.
    lines.addAll(CoreKeeperLogParserTests.connection(BOB, "222", "Bob", "2026-10-01T10:05:00Z", "x").subList(0, 7));
    return lines;
  }

  @Test
  void replayedLinesAreStoredOnceAndLinkTo7dtdBySteamId() {
    feed(log());
    feed(log());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_SESSION", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT player_id FROM M_CK_PLAYER WHERE steam_id = ?", Long.class, ALICE))
        .isEqualTo(sevenDaysPlayer);
    assertThat(jdbc.queryForObject("SELECT player_id FROM M_CK_PLAYER WHERE steam_id = ?", Long.class, BOB)).isNull();
    assertThat(sessions.onlineCount()).isEqualTo(1);
    assertThat(sessions.players()).extracting(CoreKeeperSessionQueryService.PlayerTotal::name)
        .contains("Alice", "Bob");
    assertThat(sessions.players().stream().filter(p -> p.name().equals("Alice")).findFirst().orElseThrow().total())
        .isEqualTo("1時間30分");
  }

  @Test
  void aNewConnectEndsTheOldSessionAndANameChangeIsKept() {
    feed(log());
    var parser = new CoreKeeperLogParser();
    for (String line : CoreKeeperLogParserTests.connection(BOB, "333", "Bobby", "2026-10-01T12:00:00Z", "x")
        .subList(0, 7)) {
      parser.accept(line).ifPresent(store::apply);
    }
    assertThat(jdbc.queryForObject("SELECT end_reason FROM T_CK_SESSION s JOIN M_CK_PLAYER p ON p.id = s.ck_player_id "
        + "WHERE p.steam_id = ? AND s.player_name = 'Bob'", String.class, BOB)).isEqualTo("RECONNECTED");
    assertThat(jdbc.queryForObject("SELECT player_name FROM M_CK_PLAYER WHERE steam_id = ?", String.class, BOB))
        .isEqualTo("Bobby");
    // An old line replayed later must not put the old name back.
    feed(log());
    assertThat(jdbc.queryForObject("SELECT player_name FROM M_CK_PLAYER WHERE steam_id = ?", String.class, BOB))
        .isEqualTo("Bobby");
    assertThat(store.closeOpen(OffsetDateTime.now(), CoreKeeperSessionStore.SERVER_STOPPED)).isEqualTo(1);
    assertThat(sessions.onlineCount()).isZero();
  }

  @Test
  void pagesShowNamesAndPlayTimeButNeverTheSteamId() throws Exception {
    feed(log());
    mvc.perform(get("/octarine/players").with(user("ck-member").roles("PLAYER")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("いま接続中")))
        .andExpect(content().string(containsString("Bob")))
        .andExpect(content().string(containsString("1時間30分")))
        .andExpect(content().string(not(containsString("7656119"))));
    mvc.perform(get("/portal").with(user("ck-member").roles("PLAYER")))
        .andExpect(content().string(containsString("1人が接続中")));
    mvc.perform(get("/portal/profile").with(user("ck-member").roles("PLAYER")))
        .andExpect(content().string(containsString("Core Keeper（Alice）")))
        .andExpect(content().string(containsString("1時間30分")))
        .andExpect(content().string(not(containsString("7656119"))));
    mvc.perform(get("/portal/maintenance/accounts").with(user("ck-admin").roles("ADMIN")))
        .andExpect(content().string(containsString("…0002")))
        .andExpect(content().string(not(containsString(BOB))));
    mvc.perform(get("/portal/maintenance/accounts").with(user("ck-member").roles("PLAYER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminsLinkAccountsAndDeleteRecordsOnRequest() throws Exception {
    feed(log());
    long bob = jdbc.queryForObject("SELECT id FROM M_CK_PLAYER WHERE steam_id = ?", Long.class, BOB);
    long admin = jdbc.queryForObject("SELECT id FROM M_WEB_ACCOUNT WHERE login_id = 'ck-admin'", Long.class);
    mvc.perform(post("/portal/maintenance/accounts/corekeeper").with(user("ck-member").roles("PLAYER")).with(csrf())
            .param("ckPlayerId", String.valueOf(bob)).param("accountId", String.valueOf(memberAccount)))
        .andExpect(status().isForbidden());
    mvc.perform(post("/portal/maintenance/accounts/corekeeper").with(user("ck-admin").roles("ADMIN")).with(csrf())
            .param("ckPlayerId", String.valueOf(bob)).param("accountId", String.valueOf(admin)))
        .andExpect(status().is3xxRedirection());
    mvc.perform(get("/portal/profile").with(user("ck-admin").roles("ADMIN")))
        .andExpect(content().string(containsString("Core Keeper（Bob）")))
        .andExpect(content().string(containsString("接続中")));
    mvc.perform(post("/portal/maintenance/accounts/corekeeper/delete").with(user("ck-admin").roles("ADMIN"))
            .with(csrf()).param("ckPlayerId", String.valueOf(bob)))
        .andExpect(status().is3xxRedirection());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM M_CK_PLAYER WHERE steam_id = ?", Integer.class, BOB)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_CK_SESSION", Integer.class)).isEqualTo(1);
  }
}
