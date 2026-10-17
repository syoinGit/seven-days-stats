package com.yuki.rubinco.watchpoint.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.yuki.rubinco.timeline.service.TimelinePostService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:log_atomicity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class GameLogImportTransactionTests {
  private static final String JOIN = "2026-07-26T08:18:02 1147.256 INF PlayerSpawnedInWorld "
      + "(reason: JoinMultiplayer, position: -162, 52, -857): EntityID=331, "
      + "PltfmId='Steam_123', CrossId='EOS_atomic', OwnerID='Steam_123', PlayerName='Player', ClientNumber='3'";

  @Autowired private GameLogImportService imports;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private TimelinePostService timeline;
  @TempDir Path directory;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM T_TIMELINE_POST");
    jdbc.update("DELETE FROM T_PLAYER_JOIN_TRANSACTION");
    jdbc.update("DELETE FROM T_PLAYER_CURRENT_STATE");
    jdbc.update("DELETE FROM T_PLAYER_POSITION_TRANSACTION");
    jdbc.update("DELETE FROM M_PLAYER");
  }

  @Test
  void fileImportRollsBackTheRawEventWhenTimelinePersistenceFails() throws Exception {
    Path file = directory.resolve("server.log");
    Files.writeString(file, JOIN);
    failPublishing();

    assertThatThrownBy(() -> imports.importLogFile(file)).isInstanceOf(DataIntegrityViolationException.class);
    assertEmpty();
  }

  @Test
  void streamRetainsThePendingEventForRetryAfterRollingBack() {
    var session = imports.openStreamSession("docker:test");
    session.acceptLine(JOIN);
    failPublishing();

    assertThatThrownBy(session::flush).isInstanceOf(DataIntegrityViolationException.class);
    assertEmpty();

    doCallRealMethod().when(timeline).publishGameEvent(
        any(), any(), any(), any(), any(), any(), any(), any(), any());
    assertThat(session.flush().playerJoins()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_PLAYER_JOIN_TRANSACTION", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_TIMELINE_POST", Integer.class)).isEqualTo(1);
    assertThat(session.flush().linesRead()).isZero();
  }

  private void failPublishing() {
    doThrow(new DataIntegrityViolationException("Simulated persistence failure"))
        .when(timeline).publishGameEvent(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  private void assertEmpty() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM T_PLAYER_JOIN_TRANSACTION", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM M_PLAYER", Integer.class)).isZero();
  }
}
