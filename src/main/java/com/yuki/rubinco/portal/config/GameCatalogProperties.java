package com.yuki.rubinco.portal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.games")
public class GameCatalogProperties {
  @Valid
  @NotEmpty
  private List<@NotNull @Valid Game> catalog = new ArrayList<>();

  @AssertTrue(message = "Game IDs must be unique")
  public boolean isCatalogGameIdConfigurationValid() {
    if (catalog == null) {
      return true;
    }
    if (catalog.stream().anyMatch(Objects::isNull)) {
      return false;
    }
    long uniqueIds = catalog.stream()
        .map(Game::getId)
        .filter(Objects::nonNull)
        .distinct()
        .count();
    return uniqueIds == catalog.size();
  }

  @Getter
  @Setter
  public static class Game {
    @NotBlank
    @Pattern(regexp = "[a-z0-9-]{1,40}")
    private String id;

    @NotBlank
    private String displayName;

    @NotBlank
    private String siteName;

    @NotBlank
    @Pattern(regexp = "https?://[^\\s]+")
    private String siteUrl;

    private boolean siteEnabled = true;

    private boolean manualStart = true;

    @AssertTrue(message = "Game site URL must use HTTPS, except on loopback hosts")
    public boolean isSiteUrlSecure() {
      if (siteUrl == null || siteUrl.isBlank() || !siteUrl.equals(siteUrl.strip())) {
        return false;
      }
      try {
        URI uri = URI.create(siteUrl);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null
            || uri.getFragment() != null || uri.getQuery() != null) {
          return false;
        }
        if ("https".equalsIgnoreCase(scheme)) {
          return true;
        }
        return "http".equalsIgnoreCase(scheme) && isLoopback(host);
      } catch (IllegalArgumentException exception) {
        return false;
      }
    }

    @NotBlank
    @Pattern(regexp = "[a-z0-9-]{1,30}")
    private String accent;

    @NotBlank
    private String version;

    @NotBlank
    private String stage;

    @Min(0)
    @Max(100)
    private int progress;

    @NotBlank
    private String schedule;

    @NotBlank
    private String connection;

    private boolean isLoopback(String host) {
      return "localhost".equalsIgnoreCase(host)
          || "127.0.0.1".equals(host)
          || "[::1]".equals(host)
          || "::1".equals(host);
    }
  }
}
