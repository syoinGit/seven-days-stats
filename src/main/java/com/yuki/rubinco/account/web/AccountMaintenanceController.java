package com.yuki.rubinco.account.web;

import com.yuki.rubinco.account.service.WebAccountAdminService;
import com.yuki.rubinco.octarine.session.CoreKeeperPlayerAdminService;
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
public class AccountMaintenanceController {
  private final WebAccountAdminService accountAdminService;
  private final PortalPlatformProperties platformProperties;
  private final CoreKeeperSessionQueryService coreKeeperSessions;
  private final CoreKeeperPlayerAdminService coreKeeperPlayers;

  @GetMapping({"/maintenance/accounts", "/portal/maintenance/accounts"})
  public String accounts(HttpServletRequest request, Model model) {
    if (request.getRequestURI().equals("/maintenance/accounts")
        || (platformProperties.getCognito().isEnabled() && !platformProperties.isPortalHost(request.getServerName()))) {
      return "redirect:" + platformProperties.portalPageUrl() + "/maintenance/accounts";
    }
    model.addAttribute("cognitoEnabled", platformProperties.getCognito().isEnabled());
    model.addAttribute("accountAdmin", accountAdminService.view());
    model.addAttribute("coreKeeperPlayers", coreKeeperSessions.adminPlayers());
    return "account-maintenance";
  }

  @PostMapping({"/maintenance/accounts", "/portal/maintenance/accounts"})
  public String createAccount(
      @RequestParam String loginId,
      @RequestParam String password,
      @RequestParam(defaultValue = "PLAYER") String role,
      @RequestParam(required = false) Long playerId,
      RedirectAttributes redirectAttributes) {
    try {
      accountAdminService.create(loginId, password, role, playerId);
      redirectAttributes.addFlashAttribute("notice", "アカウントを作成しました。");
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:" + platformProperties.portalPageUrl() + "/maintenance/accounts";
  }

  @PostMapping({"/maintenance/accounts/player", "/portal/maintenance/accounts/player"})
  public String linkPlayer(@RequestParam Long accountId, @RequestParam(required = false) Long playerId,
      RedirectAttributes redirectAttributes) {
    try {
      accountAdminService.linkPlayer(accountId, playerId);
      redirectAttributes.addFlashAttribute("notice", "プレイヤー連携を更新しました。");
    } catch (IllegalArgumentException exception) {
      redirectAttributes.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:" + platformProperties.portalPageUrl() + "/maintenance/accounts";
  }

  @PostMapping("/portal/maintenance/accounts/corekeeper")
  public String linkCoreKeeperPlayer(@RequestParam long ckPlayerId, @RequestParam(required = false) Long accountId,
      RedirectAttributes redirectAttributes) {
    try {
      coreKeeperPlayers.linkAccount(ckPlayerId, accountId);
      redirectAttributes.addFlashAttribute("notice", "Core Keeperの連携を更新しました。");
    } catch (IllegalArgumentException exception) {
      redirectAttributes.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:" + platformProperties.portalPageUrl() + "/maintenance/accounts";
  }

  @PostMapping("/portal/maintenance/accounts/corekeeper/delete")
  public String deleteCoreKeeperPlayer(@RequestParam long ckPlayerId, RedirectAttributes redirectAttributes) {
    try {
      coreKeeperPlayers.delete(ckPlayerId);
      redirectAttributes.addFlashAttribute("notice", "Core Keeperのプレイヤー記録を削除しました。");
    } catch (IllegalArgumentException exception) {
      redirectAttributes.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:" + platformProperties.portalPageUrl() + "/maintenance/accounts";
  }

  @PostMapping({"/maintenance/accounts/password", "/portal/maintenance/accounts/password"})
  public String changePassword(
      @RequestParam Long accountId,
      @RequestParam String password,
      @RequestParam String passwordConfirmation,
      RedirectAttributes redirectAttributes) {
    try {
      accountAdminService.changePassword(accountId, password, passwordConfirmation);
      redirectAttributes.addFlashAttribute("notice", "パスワードを変更しました。");
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:" + platformProperties.portalPageUrl() + "/maintenance/accounts";
  }
}
