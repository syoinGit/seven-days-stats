package com.yuki.rubinco.guild;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@PreAuthorize("@adminAccess.currentUserIsAdmin()")
public class GuildMaintenanceController {
  private final GuildService guild;
  private final GuildProperties properties;

  @GetMapping("/guild/maintenance")
  public String index(Model model) {
    model.addAttribute("jobs", guild.jobs());
    model.addAttribute("enabled", properties.enabled());
    model.addAttribute("bornToday", guild.bornToday());
    model.addAttribute("dailyLimit", properties.dailyLimit());
    model.addAttribute("characters", guild.characters(0));
    return "guild-maintenance";
  }

  @PostMapping("/guild/maintenance/generate")
  public String generate(RedirectAttributes redirect) {
    redirect.addFlashAttribute("notice", guild.generateToday());
    return "redirect:/guild/maintenance";
  }

  @PostMapping("/guild/maintenance/visits")
  public String visits(RedirectAttributes redirect) {
    redirect.addFlashAttribute("notice", guild.refreshVisits());
    return "redirect:/guild/maintenance";
  }

  @PostMapping("/guild/maintenance/characters/{id}/delete")
  public String delete(@PathVariable long id, RedirectAttributes redirect) {
    redirect.addFlashAttribute("notice", guild.delete(id));
    return "redirect:/guild/maintenance#residents";
  }
}
