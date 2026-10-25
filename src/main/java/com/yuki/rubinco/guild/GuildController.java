package com.yuki.rubinco.guild;

import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Every page is the tavern; the others open one of its windows (fragments/guild-windows.html) on top. */
@Controller
@RequiredArgsConstructor
public class GuildController {
  private final GuildService guild;
  private final GuildProperties properties;

  @GetMapping("/guild")
  public String room(Model model) {
    model.addAttribute("canonical", properties.publicUrl());
    return stage(model, "tavern", null, null);
  }

  @GetMapping("/guild/visitors")
  public String visitors(Model model) {
    return stage(model, "visitors", "visitors", "今日の来店者");
  }

  @GetMapping("/guild/board")
  public String board(Model model) {
    model.addAttribute("newcomers", guild.characters(0).stream().limit(1).toList());
    return stage(model, "board", "board", "依頼掲示板");
  }

  @GetMapping("/guild/characters")
  public String characters(Model model, @RequestParam(defaultValue = "0") int page) {
    int safePage = Math.max(0, Math.min(page, 10000));
    model.addAttribute("characters", guild.characters(safePage));
    model.addAttribute("page", safePage);
    return stage(model, "roster", "roster", "住人名簿");
  }

  @GetMapping("/guild/characters/{id}")
  public String character(Model model, @PathVariable long id) {
    var character = guild.character(id);
    model.addAttribute("character", character);
    return stage(model, "roster", "character", character.name());
  }

  private String stage(Model model, String tab, String window, String title) {
    model.addAttribute("room", guild.room(ZonedDateTime.now(GuildService.TOKYO)));
    model.addAttribute("tab", tab);
    model.addAttribute("window", window);
    model.addAttribute("windowTitle", title);
    return "guild";
  }
}
