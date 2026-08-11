package com.yuki.sevendays_states.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:application_configuration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class ApplicationConfigurationTests {

  @Autowired
  private Environment environment;

  @Test
  void derivesDataAndGameDirectoriesFromRoot() {
    SevenDaysDataProperties properties = new SevenDaysDataProperties(
        "production", "docker", Path.of("/srv/7dtd"), null,
        null, null, null, null, null, null);

    assertThat(properties.dataPath()).isEqualTo(Path.of("/srv/7dtd/data"));
    assertThat(properties.gamePath()).isEqualTo(Path.of("/srv/7dtd/game"));
    assertThat(properties.configPath()).isEqualTo(Path.of("/srv/7dtd/config"));
  }

  @Test
  void keepsAdminLoginAndSafeAiDefaultsInApplicationConfiguration() {
    assertThat(environment.getProperty("app.auth.bootstrap.login")).isEqualTo("admin");
    assertThat(environment.getProperty("app.ai.enabled", Boolean.class)).isFalse();
    assertThat(environment.getProperty("app.sevendays.telnet.enabled", Boolean.class)).isFalse();
  }
}
