package com.yuki.rubinco.portal.config;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortalConfigurationPropertiesTests {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void cognitoClientIdsAreRequiredAndMustBeUniqueWhenEnabled() {
    PortalPlatformProperties properties = new PortalPlatformProperties();
    properties.getCognito().setEnabled(true);
    properties.getCognito().setPortalClientId("same-client");
    properties.getCognito().setWatchpointClientId("same-client");
    properties.getCognito().setLuminiteClientId(" ");

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("cognitoClientConfigurationValid");

    properties.getCognito().setWatchpointClientId("watchpoint-client");
    properties.getCognito().setLuminiteClientId(" luminite-client");

    assertThat(validator.validate(properties)).isNotEmpty();

    properties.getCognito().setLuminiteClientId("luminite-client");

    assertThat(validator.validate(properties)).isEmpty();
  }

  @Test
  void gameCatalogRejectsDuplicateIdsAndInvalidProgress() {
    GameCatalogProperties properties = new GameCatalogProperties();
    GameCatalogProperties.Game first = game("terraria", 42);
    GameCatalogProperties.Game duplicate = game("terraria", 101);
    properties.setCatalog(List.of(first, duplicate));

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("catalogGameIdConfigurationValid", "catalog[1].progress");
  }

  @Test
  void gameCatalogAllowsLocalHttpButRejectsPublicHttp() {
    GameCatalogProperties properties = new GameCatalogProperties();
    GameCatalogProperties.Game game = game("terraria", 42);
    game.setSiteUrl("http://luminite.rubinco.link");
    properties.setCatalog(List.of(game));

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("catalog[0].siteUrlSecure");

    game.setSiteUrl("http://localhost:8080/luminite");

    assertThat(validator.validate(properties)).isEmpty();

    game.setSiteUrl("http://[::1]:8080/luminite");

    assertThat(validator.validate(properties)).isEmpty();
  }

  @Test
  void platformUrlsRejectInsecurePublicLinksAndNonOriginCorsEntries() {
    PortalPlatformProperties properties = new PortalPlatformProperties();
    properties.setPortalUrl("http://portal.rubinco.link");
    properties.setAllowedOrigins(List.of("https://portal.rubinco.link/path"));

    assertThat(validator.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("platformUrlConfigurationValid");

    properties.setPortalUrl("http://localhost:8080/portal");
    properties.setAllowedOrigins(List.of("http://localhost:8080"));

    assertThat(validator.validate(properties)).isEmpty();
  }

  private GameCatalogProperties.Game game(String id, int progress) {
    GameCatalogProperties.Game game = new GameCatalogProperties.Game();
    game.setId(id);
    game.setDisplayName("Luminite");
    game.setSiteName("Terraria");
    game.setSiteUrl("https://luminite.rubinco.link");
    game.setAccent("violet");
    game.setVersion("1.4.4");
    game.setStage("Caverns");
    game.setProgress(progress);
    game.setSchedule("週末");
    game.setConnection("準備中");
    return game;
  }
}
