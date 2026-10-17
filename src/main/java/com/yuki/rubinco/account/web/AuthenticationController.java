package com.yuki.rubinco.account.web;

import com.yuki.rubinco.account.service.GuestAuthenticationService;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AuthenticationController {
  private final GuestAuthenticationService guestAuthenticationService;
  private final PortalPlatformProperties platformProperties;

  @GetMapping("/login")
  public String login(HttpServletRequest request, Model model) {
    return "redirect:" + platformProperties.portalLoginUrl()
        + (request.getParameterMap().containsKey("error") ? "&error" : "");
  }

  @PostMapping("/guest-login")
  public String guestLogin(
      HttpServletRequest request,
      HttpServletResponse response,
      RedirectAttributes redirectAttributes) {
    if (!guestAuthenticationService.login(request, response)) {
      redirectAttributes.addFlashAttribute("error", "ゲストログインを準備できませんでした。");
      return "redirect:/login";
    }
    return "redirect:/portal#timeline";
  }
}
