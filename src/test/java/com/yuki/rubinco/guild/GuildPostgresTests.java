package com.yuki.rubinco.guild;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run only against a disposable empty database; the inherited tests delete guild test data. */
@EnabledIfEnvironmentVariable(named = "GUILD_TEST_POSTGRES_URL", matches = ".+")
class GuildPostgresTests extends GuildTests {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("GUILD_TEST_POSTGRES_URL"));
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("GUILD_TEST_POSTGRES_PASSWORD", ""));
  }
}
