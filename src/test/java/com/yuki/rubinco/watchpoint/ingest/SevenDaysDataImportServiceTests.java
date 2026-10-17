package com.yuki.rubinco.watchpoint.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.yuki.rubinco.watchpoint.persistence.repository.M_PlayerRepository;
import com.yuki.rubinco.watchpoint.persistence.repository.T_ImportRunRepository;
import com.yuki.rubinco.watchpoint.persistence.repository.T_PlayerMarkerSnapshotRepository;
import com.yuki.rubinco.watchpoint.persistence.repository.T_PlayerStateSnapshotRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rubinco_data_import;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false",
    "app.sevendays.root=target/test-7dtd-data-import"
})
class SevenDaysDataImportServiceTests {

  private static final Path GAME_DIR = Path.of("target/test-7dtd-data-import/data/Saves/TestWorld/TestGame");

  @Autowired
  private SevenDaysDataImportService importService;

  @Autowired
  private M_PlayerRepository playerRepository;

  @Autowired
  private T_PlayerStateSnapshotRepository playerStateSnapshotRepository;

  @Autowired
  private T_PlayerMarkerSnapshotRepository playerMarkerSnapshotRepository;

  @Autowired
  private T_ImportRunRepository importRunRepository;

  @Autowired
  private com.yuki.rubinco.watchpoint.persistence.repository.M_ServerConfigSettingRepository settings;

  @BeforeEach
  void resetData() throws Exception {
    playerMarkerSnapshotRepository.deleteAll();
    playerStateSnapshotRepository.deleteAll();
    importRunRepository.deleteAll();
    playerRepository.deleteAll();
    Files.createDirectories(GAME_DIR);
    Files.deleteIfExists(Path.of("target/test-7dtd-data-import/config/serverconfig.xml"));
    settings.deleteAll();
  }

  @Test
  void importingSameEosPlayerDoesNotIncreasePlayerMaster() throws Exception {
    writePlayersXml("""
        <player platform="EOS" userid="00024b5c4d2546468b7c6775bd927c32" nativeplatform="Steam" nativeuserid="76561198382915826" playername="魅惑のこし餡ぼでぃ" playgroup="Standalone" lastlogin="2026-07-25 22:45:07" position="464,47,-602" />
        """);

    importService.importCurrentData();
    importService.importCurrentData();

    assertThat(playerRepository.findAll())
        .extracting(player -> player.getPlayerKey() + ":" + player.getPlayerName())
        .containsExactly("EOS:00024b5c4d2546468b7c6775bd927c32:魅惑のこし餡ぼでぃ");
  }

  @Test
  void importingSamePlayerStateWithDifferentFileModifiedTimeDoesNotIncreaseSnapshots() throws Exception {
    String playerXml = """
        <player platform="EOS" userid="00024b5c4d2546468b7c6775bd927c32" nativeplatform="Steam" nativeuserid="76561198382915826" playername="魅惑のこし餡ぼでぃ" playgroup="Standalone" lastlogin="2026-07-25 22:45:07" position="464,47,-602" />
        """;
    writePlayersXml(playerXml);
    Files.setLastModifiedTime(GAME_DIR.resolve("players.xml"), FileTime.from(Instant.parse("2026-07-26T01:00:00Z")));
    importService.importCurrentData();

    writePlayersXml(playerXml);
    Files.setLastModifiedTime(GAME_DIR.resolve("players.xml"), FileTime.from(Instant.parse("2026-07-26T02:00:00Z")));
    importService.importCurrentData();

    assertThat(playerRepository.count()).isEqualTo(1);
    assertThat(playerStateSnapshotRepository.count()).isEqualTo(1);
  }

  @Test
  void steamMatchUpdatesExistingPlayerWhenCanonicalKeyChangesToEos() throws Exception {
    writePlayersXml("""
        <player platform="Steam" userid="76561198382915826" playername="PlayerBefore" playgroup="Standalone" lastlogin="2026-07-25 22:45:07" position="464,47,-602" />
        """);
    importService.importCurrentData();

    writePlayersXml("""
        <player platform="EOS" userid="00024b5c4d2546468b7c6775bd927c32" nativeplatform="Steam" nativeuserid="76561198382915826" playername="PlayerAfter" playgroup="Standalone" lastlogin="2026-07-26 01:45:07" position="500,47,-602" />
        """);
    importService.importCurrentData();

    assertThat(playerRepository.findAll())
        .extracting(player -> player.getPlayerKey() + ":" + player.getPlayerName() + ":" + player.getNativeUserId())
        .containsExactly("EOS:00024b5c4d2546468b7c6775bd927c32:PlayerAfter:76561198382915826");
  }

  @Test
  void sameNameWithDifferentExternalIdsCreatesDifferentPlayers() throws Exception {
    writePlayersXml("""
        <player platform="EOS" userid="eos-a" nativeplatform="Steam" nativeuserid="steam-a" playername="SameName" playgroup="Standalone" lastlogin="2026-07-25 22:45:07" position="1,2,3" />
        <player platform="EOS" userid="eos-b" nativeplatform="Steam" nativeuserid="steam-b" playername="SameName" playgroup="Standalone" lastlogin="2026-07-25 22:45:07" position="4,5,6" />
        """);

    importService.importCurrentData();

    assertThat(playerRepository.findAll())
        .extracting(player -> player.getPlayerKey() + ":" + player.getPlayerName())
        .containsExactlyInAnyOrder("EOS:eos-a:SameName", "EOS:eos-b:SameName");
  }

  @Test
  void failedImportsRollbackDataButKeepTheirAuditAndAllowRetry() throws Exception {
    Path config = Path.of("target/test-7dtd-data-import/config/serverconfig.xml");
    Files.createDirectories(config.getParent());
    Files.writeString(config, "<ServerSettings><property name=\"ServerName\" value=\"Rollback\" /></ServerSettings>");
    Files.writeString(GAME_DIR.resolve("players.xml"), "<broken>");

    assertThatThrownBy(importService::importCurrentData).isInstanceOf(IllegalStateException.class);
    assertThat(importRunRepository.findAll()).singleElement().satisfies(run -> {
      assertThat(run.getStatus()).isEqualTo("FAILED");
      assertThat(run.getFinishedAt()).isNotNull();
      assertThat(run.getMessage()).isNotBlank();
    });
    assertThat(playerStateSnapshotRepository.count()).isZero();
    assertThat(settings.count()).isZero();

    writePlayersXml("<player platform=\"EOS\" userid=\"retry\" playername=\"Retry\" />");
    importService.importCurrentData();

    assertThat(importRunRepository.findAll()).extracting(run -> run.getStatus())
        .containsExactlyInAnyOrder("FAILED", "SUCCESS");
    assertThat(playerRepository.findAll()).extracting(player -> player.getPlayerName())
        .containsExactly("Retry");
  }

  private void writePlayersXml(String players) throws Exception {
    Files.writeString(GAME_DIR.resolve("players.xml"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <persistentplayerdata version="1">
        %s
        </persistentplayerdata>
        """.formatted(players));
  }
}
