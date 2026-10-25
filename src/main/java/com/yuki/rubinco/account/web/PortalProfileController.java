package com.yuki.rubinco.account.web;

import com.yuki.rubinco.account.service.CurrentWebAccountService;
import com.yuki.rubinco.account.service.PortalProfileService;
import com.yuki.rubinco.octarine.session.CoreKeeperSessionQueryService;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class PortalProfileController {
  private final PortalPlatformProperties platformProperties;
  private final PortalProfileService profiles;
  private final CurrentWebAccountService currentAccounts;
  private final CoreKeeperSessionQueryService coreKeeperSessions;

  @GetMapping("/portal/profile")
  public String profile(HttpServletRequest request, Model model,
      org.springframework.security.core.Authentication authentication) {
    if (platformProperties.getCognito().isEnabled() && !platformProperties.isPortalHost(request.getServerName())) {
      return "redirect:" + platformProperties.portalPageUrl() + "/profile";
    }
    if (model.getAttribute("currentProfile") == null) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
    }
    model.addAttribute("avatarOptions", PortalProfileService.AVATARS);
    model.addAttribute("coreKeeperPlayTimes", currentAccounts.current(authentication)
        .map(account -> coreKeeperSessions.forAccount(account.getId(), account.getPlayerId()))
        .orElse(java.util.List.of()));
    return "portal-profile";
  }

  @PostMapping("/portal/profile")
  public String updateProfile(org.springframework.security.core.Authentication authentication,
      @RequestParam String displayName, @RequestParam String avatarKey, RedirectAttributes redirect) {
    try {
      profiles.update(authentication, displayName, avatarKey);
      redirect.addFlashAttribute("notice", "プロフィールを更新しました。");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/portal/profile";
  }
}
