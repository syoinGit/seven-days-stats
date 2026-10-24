package com.yuki.rubinco.shared.web;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.service.CurrentWebAccountService;
import com.yuki.rubinco.account.service.PortalProfileService;
import com.yuki.rubinco.account.web.AccountMaintenanceController;
import com.yuki.rubinco.account.web.AuthenticationController;
import com.yuki.rubinco.account.web.PortalProfileController;
import com.yuki.rubinco.luminite.web.LuminiteController;
import com.yuki.rubinco.luminite.web.LuminiteMaintenanceController;
import com.yuki.rubinco.octarine.web.OctarineController;
import com.yuki.rubinco.octarine.web.OctarineMaintenanceController;
import com.yuki.rubinco.portal.config.PortalPlatformProperties;
import com.yuki.rubinco.portal.web.PortalController;
import com.yuki.rubinco.watchpoint.web.AiAnalysisMaintenanceController;
import com.yuki.rubinco.watchpoint.web.DashboardController;
import com.yuki.rubinco.watchpoint.web.DiaryController;
import com.yuki.rubinco.watchpoint.web.SurvivorMarkMaintenanceController;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {
    DashboardController.class,
    DiaryController.class,
    PortalController.class,
    LuminiteController.class,
    LuminiteMaintenanceController.class,
    OctarineController.class,
    OctarineMaintenanceController.class,
    AuthenticationController.class,
    PortalProfileController.class,
    AccountMaintenanceController.class,
    AiAnalysisMaintenanceController.class,
    SurvivorMarkMaintenanceController.class
})
@RequiredArgsConstructor
public class WebAuthModelAdvice {

  private final CurrentWebAccountService currentAccountService;
  private final PortalPlatformProperties platformProperties;
  private final PortalProfileService profiles;

  @ModelAttribute
  public void addAuthenticationModel(Model model, Authentication authentication) {
    Optional<M_WebAccount> account = currentAccountService.current(authentication);
    model.addAttribute("loggedIn", account.isPresent());
    boolean guest = account.isEmpty() || "VIEWER".equals(account.get().getRole());
    model.addAttribute("profileDirectory", guest
        ? new PortalProfileService.Directory(java.util.Map.of(), java.util.Map.of())
        : profiles.directory());
    var currentProfile = account.map(profiles::profile).orElse(null);
    model.addAttribute("currentProfile", currentProfile);
    model.addAttribute("currentAvatar", guest ? profiles.url("guest") : currentProfile.avatarUrl());
    model.addAttribute("currentDisplayName", guest ? "ゲスト" : currentProfile.displayName());
    model.addAttribute("currentAccountLogin", account.map(M_WebAccount::getLoginId).orElse(null));
    model.addAttribute("currentPlayerId", account.flatMap(value -> Optional.ofNullable(value.getPlayerId())).orElse(null));
    model.addAttribute("currentAdmin", account.map(value -> "ADMIN".equals(value.getRole())).orElse(false));
    model.addAttribute("currentViewer", account.map(value -> "VIEWER".equals(value.getRole())).orElse(false));
    model.addAttribute("portalUrl", platformProperties.portalPageUrl());
    model.addAttribute("watchpointUrl", platformProperties.getWatchpointUrl());
    model.addAttribute("luminiteUrl", platformProperties.getLuminiteUrl());
    model.addAttribute("octarineUrl", platformProperties.getOctarineUrl());
  }
}
