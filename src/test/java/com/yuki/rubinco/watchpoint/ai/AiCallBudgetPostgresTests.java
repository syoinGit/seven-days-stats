package com.yuki.rubinco.watchpoint.ai;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run only against a disposable empty database (the same one as GuildPostgresTests); the tests delete the budget rows. */
@EnabledIfEnvironmentVariable(named = "GUILD_TEST_POSTGRES_URL", matches = ".+")
class AiCallBudgetPostgresTests extends AiCallBudgetTests {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("GUILD_TEST_POSTGRES_URL"));
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("GUILD_TEST_POSTGRES_PASSWORD", ""));
  }
}
