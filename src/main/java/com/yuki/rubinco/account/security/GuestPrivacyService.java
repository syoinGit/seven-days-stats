package com.yuki.rubinco.account.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/** Masks external platform IDs and removes dossier links for read-only guests. */
@Service
public class GuestPrivacyService {

  private static final String PRIVACY_BADGE = """
      <aside class="privacy-badge" aria-label="guest privacy mode">
        <b>GUEST VIEW</b><span>ゲストとして閲覧しています</span>
      </aside>
      """;
  private static final String PRIVACY_STYLESHEET =
      "<link rel=\"stylesheet\" href=\"/css/app.css?v=20260805-3\">";
  private static final Pattern EXTERNAL_ID =
      Pattern.compile("(?i)(?:Steam|EOS)(?:_|:)[A-Za-z0-9-]+");

  public String protectHtml(String html) {
    String protectedHtml = EXTERNAL_ID.matcher(html).replaceAll("EXTERNAL-ID")
        .replaceAll("(?i)href=(\\\"|')/players/[^\\\"']*(\\\"|')", "href=\"/dashboard\"")
        .replaceAll("(?i)</head>", Matcher.quoteReplacement(PRIVACY_STYLESHEET) + "</head>")
        .replaceAll("(?i)</body>", Matcher.quoteReplacement(PRIVACY_BADGE) + "</body>");
    if (!protectedHtml.contains("class=\"privacy-badge\"")) {
      protectedHtml += PRIVACY_BADGE;
    }
    return protectedHtml;
  }

  public static boolean isGuest(Authentication authentication) {
    return authentication != null && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch("ROLE_VIEWER"::equals);
  }
}
