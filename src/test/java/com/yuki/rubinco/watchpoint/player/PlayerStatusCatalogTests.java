package com.yuki.rubinco.watchpoint.player;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PlayerStatusCatalogTests {

  @Test
  void normalizesStatusAndProvidesPresentationLabel() {
    assertThat(PlayerStatusCatalog.normalize(" eating ")).isEqualTo("EATING");
    assertThat(PlayerStatusCatalog.label("EATING")).isEqualTo("ごはん中");
    assertThat(PlayerStatusCatalog.displayLabel("EATING")).isEqualTo("🍚 ごはん中");
  }

  @Test
  void resolvesChatAliasesAndIgnoresUnknownOrNullCommands() {
    assertThat(PlayerStatusCatalog.fromChatCommand("!飯")).isEqualTo("EATING");
    assertThat(PlayerStatusCatalog.fromChatCommand(" !solo ")).isEqualTo("SOLO");
    assertThat(PlayerStatusCatalog.fromChatCommand("!unknown")).isNull();
    assertThat(PlayerStatusCatalog.fromChatCommand(null)).isNull();
  }
}
