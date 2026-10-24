package com.yuki.rubinco.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import com.yuki.rubinco.portal.config.GameCatalogProperties;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import com.yuki.rubinco.watchpoint.ingest.SevenDaysDataProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

class ApplicationConfigurationTests {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(PropertiesConfiguration.class);

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
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      Environment environment = context.getEnvironment();
      assertThat(environment.getProperty("app.auth.bootstrap.login")).isEqualTo("admin");
      assertThat(environment.getProperty("app.ai.enabled", Boolean.class)).isFalse();
      assertThat(context.getBean(SevenDaysDataProperties.class).telnet().enabled()).isFalse();
    });
  }

  @Test
  void productionNeedsNoPortPathOrSiteUrlOverrides() {
    productionRunner().run(context -> {
      assertThat(context).hasNotFailed();
      Environment environment = context.getEnvironment();
      SevenDaysDataProperties game = context.getBean(SevenDaysDataProperties.class);
      PortalPlatformProperties platform = context.getBean(PortalPlatformProperties.class);

      assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8082);
      assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class))
          .isTrue();
      assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("database-test");
      assertThat(game.mode()).isEqualTo("docker");
      assertThat(game.root()).isEqualTo(Path.of("/home/ec2-user/7dtd"));
      assertThat(game.serverConfigPath())
          .isEqualTo(Path.of("/home/ec2-user/7dtd/data/serverconfig.xml"));
      assertThat(game.telnet().enabled()).isTrue();
      assertThat(game.telnet().password()).isEqualTo("telnet-test");
      assertThat(platform.getPortalUrl()).isEqualTo("https://portal.rubinco.link");
      assertThat(platform.getCognito().isEnabled()).isTrue();
      assertThat(environment.getProperty("app.ai.enabled", Boolean.class)).isFalse();
      assertThat(environment.getProperty("app.survivor-karen.image-enabled", Boolean.class))
          .isFalse();
      assertSiteReferences(environment, platform, context.getBean(GameCatalogProperties.class));
    });
  }

  @Test
  void changingOnlyDomainUpdatesSitesCorsCatalogAndCognitoCallbacks() {
    productionRunner().withPropertyValues("DOMAIN=games.example.com").run(context -> {
      assertThat(context).hasNotFailed();
      PortalPlatformProperties platform = context.getBean(PortalPlatformProperties.class);
      assertThat(platform.getPortalUrl()).isEqualTo("https://portal.games.example.com");
      assertThat(platform.getWatchpointUrl()).isEqualTo("https://watchpoint.games.example.com");
      assertThat(platform.getLuminiteUrl()).isEqualTo("https://luminite.games.example.com");
      assertThat(platform.getAuthUrl()).isEqualTo("https://auth.games.example.com");
      assertThat(platform.getApiUrl()).isEqualTo("https://api.games.example.com");
      assertSiteReferences(context.getEnvironment(), platform,
          context.getBean(GameCatalogProperties.class));
    });
  }

  @Test
  void existingDeploymentOverridesStillTakePrecedence() {
    productionRunner().withPropertyValues(
        "server.port=9090",
        "SEVEN_DAYS_ROOT=/srv/game",
        "SEVEN_DAYS_CONFIG_DIRECTORY=/etc/7dtd",
        "SEVEN_DAYS_TELNET_ENABLED=false",
        "WATCHPOINT_AI_ENABLED=true",
        "SURVIVOR_KAREN_IMAGE_ENABLED=true",
        "WATCHPOINT_IMAGE_BUCKET=test-images",
        "WATCHPOINT_IMAGE_PUBLIC_BASE_URL=https://images.example.com",
        "WATCHPOINT_BOOTSTRAP_PASSWORD=bootstrap-test",
        "PORTAL_SITE_URL=https://portal.example.com",
        "WATCHPOINT_SITE_URL=https://watchpoint.example.com",
        "LUMINITE_SITE_URL=https://luminite.example.com",
        "AUTH_SITE_URL=https://auth.example.com",
        "API_BASE_URL=https://api.example.com",
        "TERRARIA_SITE_NAME=Moonlight").run(context -> {
          assertThat(context).hasNotFailed();
          Environment environment = context.getEnvironment();
          SevenDaysDataProperties game = context.getBean(SevenDaysDataProperties.class);
          PortalPlatformProperties platform = context.getBean(PortalPlatformProperties.class);
          GameCatalogProperties catalog = context.getBean(GameCatalogProperties.class);
          assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(9090);
          assertThat(game.root()).isEqualTo(Path.of("/srv/game"));
          assertThat(game.serverConfigPath()).isEqualTo(Path.of("/etc/7dtd/serverconfig.xml"));
          assertThat(game.telnet().enabled()).isFalse();
          assertThat(environment.getProperty("app.ai.enabled", Boolean.class)).isTrue();
          assertThat(environment.getProperty("app.survivor-karen.image-enabled", Boolean.class))
              .isTrue();
          assertThat(environment.getProperty("app.survivor-karen.image-bucket"))
              .isEqualTo("test-images");
          assertThat(environment.getProperty("app.survivor-karen.image-public-base-url"))
              .isEqualTo("https://images.example.com");
          assertThat(environment.getProperty("app.auth.bootstrap.password"))
              .isEqualTo("bootstrap-test");
          assertThat(platform.getPortalUrl()).isEqualTo("https://portal.example.com");
          assertThat(platform.getWatchpointUrl()).isEqualTo("https://watchpoint.example.com");
          assertThat(platform.getLuminiteUrl()).isEqualTo("https://luminite.example.com");
          assertThat(platform.getAuthUrl()).isEqualTo("https://auth.example.com");
          assertThat(platform.getApiUrl()).isEqualTo("https://api.example.com");
          assertThat(catalog.getCatalog().get(1).getDisplayName()).isEqualTo("Moonlight");
          assertSiteReferences(environment, platform, catalog);
        });
  }

  @Test
  void productionConfigDirectoryFollowsRootOverride() {
    productionRunner().withPropertyValues("SEVEN_DAYS_ROOT=/srv/game").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(SevenDaysDataProperties.class).serverConfigPath())
          .isEqualTo(Path.of("/srv/game/data/serverconfig.xml"));
    });
  }

  @Test
  void localLinksFollowPortWithoutDuplicatingTheGameCatalog() {
    runner.withPropertyValues("spring.profiles.active=local", "server.port=9091").run(context -> {
      assertThat(context).hasNotFailed();
      Environment environment = context.getEnvironment();
      PortalPlatformProperties platform = context.getBean(PortalPlatformProperties.class);
      GameCatalogProperties catalog = context.getBean(GameCatalogProperties.class);
      SevenDaysDataProperties game = context.getBean(SevenDaysDataProperties.class);
      assertThat(platform.getPortalUrl()).isEqualTo("http://localhost:9091/portal");
      assertThat(platform.getWatchpointUrl()).isEqualTo("http://localhost:9091/dashboard");
      assertThat(platform.getLuminiteUrl()).isEqualTo("http://localhost:9091/luminite");
      assertThat(platform.getApiUrl()).isEqualTo("http://localhost:9091/api");
      assertThat(platform.getAllowedOrigins()).containsExactly("http://localhost:9091");
      assertThat(platform.getCognito().isEnabled()).isFalse();
      assertThat(catalog.getCatalog().stream().filter(GameCatalogProperties.Game::isSiteEnabled).toList())
          .extracting(GameCatalogProperties.Game::getSiteUrl)
          .containsExactly(platform.getWatchpointUrl(), platform.getLuminiteUrl());
      assertThat(catalog.getCatalog()).extracting(GameCatalogProperties.Game::getId)
          .containsExactly("7dtd", "terraria", "corekeeper", "minecraft");
      assertThat(game.mode()).isEqualTo("file");
      assertThat(game.root()).isEqualTo(Path.of("7dtd"));
      assertThat(game.telnet().enabled()).isFalse();
      assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class))
          .isFalse();
    });
  }

  private ApplicationContextRunner productionRunner() {
    return runner.withPropertyValues(
        "spring.profiles.active=production,cognito",
        "POSTGRES_PASSWORD=database-test",
        "SEVEN_DAYS_TELNET_PASSWORD=telnet-test",
        "COGNITO_ISSUER_URI=https://cognito.example.com/test-pool",
        "COGNITO_PORTAL_CLIENT_ID=portal-client",
        "COGNITO_WATCHPOINT_CLIENT_ID=watchpoint-client",
        "COGNITO_LUMINITE_CLIENT_ID=luminite-client");
  }

  private void assertSiteReferences(
      Environment environment, PortalPlatformProperties platform, GameCatalogProperties catalog) {
    assertThat(platform.getAllowedOrigins()).containsExactly(
        platform.getPortalUrl(), platform.getWatchpointUrl(), platform.getLuminiteUrl());
    assertThat(catalog.getCatalog().stream().filter(GameCatalogProperties.Game::isSiteEnabled).toList())
        .extracting(GameCatalogProperties.Game::getSiteUrl)
        .containsExactly(platform.getWatchpointUrl(), platform.getLuminiteUrl());
    String registrationPrefix = "spring.security.oauth2.client.registration.";
    assertThat(environment.getProperty(registrationPrefix + "portal.redirect-uri"))
        .isEqualTo(platform.getPortalUrl() + "/login/oauth2/code/portal");
    assertThat(environment.getProperty(registrationPrefix + "watchpoint.redirect-uri"))
        .isEqualTo(platform.getWatchpointUrl() + "/login/oauth2/code/watchpoint");
    assertThat(environment.getProperty(registrationPrefix + "luminite.redirect-uri"))
        .isEqualTo(platform.getLuminiteUrl() + "/login/oauth2/code/luminite");
    assertThat(environment.getProperty(registrationPrefix + "portal.client-id"))
        .isEqualTo(platform.getCognito().getPortalClientId());
    assertThat(environment.getProperty(registrationPrefix + "watchpoint.client-id"))
        .isEqualTo(platform.getCognito().getWatchpointClientId());
    assertThat(environment.getProperty(registrationPrefix + "luminite.client-id"))
        .isEqualTo(platform.getCognito().getLuminiteClientId());
    assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
        .isEqualTo(environment.getProperty("spring.security.oauth2.client.provider.cognito.issuer-uri"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
      SevenDaysDataProperties.class, PortalPlatformProperties.class, GameCatalogProperties.class
  })
  static class PropertiesConfiguration {
  }
}
