package com.yuki.rubinco.portal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.platform")
public class PortalPlatformProperties {
  @NotBlank
  @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
  private String domain = "rubinco.link";

  @NotBlank
  private String portalUrl = "https://portal.rubinco.link";

  @NotBlank
  private String watchpointUrl = "https://watchpoint.rubinco.link";

  @NotBlank
  private String luminiteUrl = "https://luminite.rubinco.link";

  @NotBlank
  private String octarineUrl = "https://octarine.rubinco.link";

  @NotBlank
  private String authUrl = "https://auth.rubinco.link";

  @NotBlank
  private String apiUrl = "https://api.rubinco.link";

  @NotEmpty
  private List<@NotBlank String> allowedOrigins = List.of(
      "https://portal.rubinco.link",
      "https://watchpoint.rubinco.link",
      "https://luminite.rubinco.link",
      "https://octarine.rubinco.link");

  @Valid
  @NotNull
  private Cognito cognito = new Cognito();

  @AssertTrue(message = "Cognito client IDs must be non-blank and unique when Cognito is enabled")
  public boolean isCognitoClientConfigurationValid() {
    if (cognito == null || !cognito.isEnabled()) {
      return true;
    }
    List<String> clientIds = cognito.dedicatedClientIds();
    return clientIds.stream().allMatch(PortalPlatformProperties::isTrimmedText)
        && new HashSet<>(clientIds).size() == clientIds.size();
  }

  @AssertTrue(message = "Platform URLs and CORS origins must be valid HTTP(S) locations")
  public boolean isPlatformUrlConfigurationValid() {
    if (!isHttpUrl(portalUrl) || !isHttpUrl(watchpointUrl) || !isHttpUrl(luminiteUrl)
        || !isHttpUrl(octarineUrl)
        || !isHttpOrigin(authUrl) || !isHttpUrl(apiUrl) || allowedOrigins == null) {
      return false;
    }
    return allowedOrigins.stream().allMatch(PortalPlatformProperties::isHttpOrigin)
        && new HashSet<>(allowedOrigins).size() == allowedOrigins.size();
  }

  public String registrationForHost(String host) {
    String normalized = normalizeHost(host);
    String normalizedDomain = normalizeHost(domain);
    if (normalized.equals("portal." + normalizedDomain)) return "portal";
    if (normalized.equals("luminite." + normalizedDomain)) return "luminite";
    if (normalized.equals("octarine." + normalizedDomain)) return "octarine";
    return "watchpoint";
  }

  public String portalPageUrl() {
    return cognito.isEnabled() ? URI.create(portalUrl).resolve("/portal").toString() : "/portal";
  }

  public String portalLoginUrl() {
    return portalPageUrl() + "?login=true";
  }

  public boolean isPortalHost(String host) {
    return normalizeHost(URI.create(portalUrl).getHost()).equals(normalizeHost(host));
  }

  // Each site owns its session cookie. Cognito SSO establishes the destination session.
  public String siteEntryUrl(String siteUrl) {
    if (!cognito.isEnabled()) return siteUrl;
    URI site = URI.create(siteUrl);
    return site.resolve("/oauth2/authorization/" + registrationForHost(site.getHost())).toString();
  }

  public String siteUrlForHost(String host) {
    return switch (registrationForHost(host)) {
      case "portal" -> portalUrl;
      case "luminite" -> luminiteUrl;
      case "octarine" -> octarineUrl;
      default -> watchpointUrl;
    };
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean isTrimmedText(String value) {
    return hasText(value) && value.equals(value.strip());
  }

  private static boolean isHttpUrl(String value) {
    if (!isTrimmedText(value)) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (host == null || uri.getUserInfo() != null
          || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
        return false;
      }
      return !"http".equalsIgnoreCase(scheme) || isLoopbackHost(host);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isHttpOrigin(String value) {
    if (!isHttpUrl(value)) {
      return false;
    }
    URI uri = URI.create(value);
    String path = uri.getPath();
    return (path == null || path.isEmpty())
        && uri.getQuery() == null && uri.getFragment() == null;
  }

  private static boolean isLoopbackHost(String host) {
    return "localhost".equalsIgnoreCase(host)
        || "127.0.0.1".equals(host)
        || "[::1]".equals(host)
        || "::1".equals(host);
  }

  private static String normalizeHost(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    int portSeparator = normalized.indexOf(':');
    if (portSeparator >= 0) {
      normalized = normalized.substring(0, portSeparator);
    }
    return normalized.endsWith(".")
        ? normalized.substring(0, normalized.length() - 1)
        : normalized;
  }

  @Getter
  @Setter
  public static class Cognito {
    private boolean enabled;
    private String portalClientId;
    private String watchpointClientId;
    private String luminiteClientId;
    /**
     * Optional until the Octarine app client exists in Cognito. While blank, the Octarine
     * registration reuses the portal client (see application-cognito.yml).
     */
    private String octarineClientId;

    /** Every configured site client, each of which must be unique. */
    public List<String> dedicatedClientIds() {
      List<String> ids = new java.util.ArrayList<>(Arrays.asList(
          portalClientId, watchpointClientId, luminiteClientId));
      if (hasText(octarineClientId)) ids.add(octarineClientId);
      return ids;
    }

    public String clientId(String registration) {
      return switch (registration) {
        case "portal" -> portalClientId;
        case "luminite" -> luminiteClientId;
        case "octarine" -> hasText(octarineClientId) ? octarineClientId : portalClientId;
        default -> watchpointClientId;
      };
    }
  }
}
