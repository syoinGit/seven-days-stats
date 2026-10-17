package com.yuki.rubinco.portal.config;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SiteHostRoutingFilterTests {
  private final PortalPlatformProperties properties = new PortalPlatformProperties();
  private final SiteHostRoutingFilter filter = new SiteHostRoutingFilter(properties);

  @Test
  void routesPortalAndLuminiteHostsFromTheirRoot() throws Exception {
    assertThat(location("localhost")).isEqualTo("/portal");
    assertThat(location("rubinco.link")).isEqualTo("/portal");
    assertThat(location("portal.rubinco.link")).isEqualTo("/portal");
    assertThat(location("luminite.rubinco.link")).isEqualTo("/luminite");
    assertThat(location("PORTAL.RUBINCO.LINK.:443")).isEqualTo("/portal");
  }

  @Test
  void preservesWatchpointRoot() throws Exception {
    MockHttpServletRequest request = request("watchpoint.rubinco.link");
    MockHttpServletResponse response = new MockHttpServletResponse();
    boolean[] continued = {false};
    FilterChain chain = (ignoredRequest, ignoredResponse) -> continued[0] = true;

    filter.doFilter(request, response, chain);

    assertThat(continued[0]).isTrue();
    assertThat(response.getRedirectedUrl()).isNull();
  }

  private String location(String host) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request(host), response, (ignoredRequest, ignoredResponse) -> { });
    return response.getRedirectedUrl();
  }

  private MockHttpServletRequest request(String host) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setServerName(host);
    return request;
  }
}
