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
  private final GuildSourceService sources;
  private final GuildInteractionService interactions;
  private final GuildWorld world;
  private final GuildDungeon dungeon;

  @GetMapping("/guild/maintenance")
  public String index(Model model) {
    model.addAttribute("jobs", guild.jobs());
    model.addAttribute("enabled", properties.enabled());
    model.addAttribute("bornToday", guild.bornToday());
    model.addAttribute("dailyLimit", properties.dailyLimit());
    model.addAttribute("characters", guild.characters(0));
    model.addAttribute("inventory", sources.inventory(java.time.LocalDate.now(GuildService.TOKYO)));
    model.addAttribute("recentCategories", sources.recentCategories().stream()
        .map(category -> category == null ? "（分類なし）" : GuildSourceService.CATEGORIES.getOrDefault(category, category)).toList());
    model.addAttribute("categoryWindow", properties.categoryWindow());
    model.addAttribute("categoryCap", properties.categoryCap());
    model.addAttribute("worlds", guild.worlds());
    model.addAttribute("hourlyJobs", interactions.jobs());
    model.addAttribute("interactionHours", properties.interactionHours());
    model.addAttribute("interactionDailyMax", properties.interactionDailyMax());
    model.addAttribute("affiliationCounts", world.counts());
    model.addAttribute("currentSlot", GuildInteractionService.slot(java.time.ZonedDateTime.now(GuildService.TOKYO)));
    model.addAttribute("dungeonDeparture", properties.dungeonDeparture());
    model.addAttribute("dungeonStageMinutes", properties.dungeonStageMinutes());
    // Administrators see every run, including today's before it is back (the result itself stays hidden until then).
    model.addAttribute("runs", dungeon.recent(java.time.ZonedDateTime.now(GuildService.TOKYO), null, 10));
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

  /** Runs this hour's interaction now instead of at a few minutes past; an hour is still filled only once. */
  @PostMapping("/guild/maintenance/interaction")
  public String interaction(RedirectAttributes redirect) {
    redirect.addFlashAttribute("notice", interactions.runHour(java.time.ZonedDateTime.now(GuildService.TOKYO)));
    return "redirect:/guild/maintenance#interactions";
  }

  /** Sends today's party now instead of at the departure time; still once a day. */
  @PostMapping("/guild/maintenance/dungeon")
  public String dungeon(RedirectAttributes redirect) {
    redirect.addFlashAttribute("notice", dungeon.depart(java.time.ZonedDateTime.now(GuildService.TOKYO)));
    return "redirect:/guild/maintenance#dungeon";
  }

  @PostMapping("/guild/maintenance/characters/{id}/delete")
  public String delete(@PathVariable long id, RedirectAttributes redirect) {
    redirect.addFlashAttribute("notice", guild.delete(id));
    return "redirect:/guild/maintenance#residents";
  }
}
