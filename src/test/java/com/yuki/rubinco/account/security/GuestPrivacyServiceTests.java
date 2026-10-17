package com.yuki.rubinco.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class GuestPrivacyServiceTests {

  private final GuestPrivacyService service = new GuestPrivacyService();

  @Test
  void preservesDisplayNamesWhileRemovingDossierLinks() {
    String html = "<body><a href=\"/players/42\">魅惑のこし餡ぼでぃ</a>がDDD烈火王テムジンを発見した</body>";

    assertThat(service.protectHtml(html))
        .contains("魅惑のこし餡ぼでぃ", "DDD烈火王テムジン", "GUEST VIEW")
        .contains("href=\"/dashboard\"")
        .doesNotContain("/players/42", "SURVIVOR-", "匿名化");
  }

  @Test
  void preservesNamesAndEscapedAttributesWhileMaskingExternalIds() {
    String html = "<body title=\"Alpha &amp; Beta\">Alpha &amp; Beta / Steam_76561198000000000 / EOS:abcdef</body>";

    assertThat(service.protectHtml(html))
        .contains("title=\"Alpha &amp; Beta\"")
        .contains("Alpha &amp; Beta / EXTERNAL-ID / EXTERNAL-ID")
        .doesNotContain("Steam_76561198000000000", "EOS:abcdef", "SURVIVOR-");
  }
}
