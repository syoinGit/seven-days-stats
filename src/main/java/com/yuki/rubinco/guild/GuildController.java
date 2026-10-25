package com.yuki.rubinco.guild;

import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class GuildController {
  private final GuildService guild;
  private final GuildProperties properties;

  @GetMapping("/guild")
  public String room(Model model) {
    model.addAttribute("room", guild.room(ZonedDateTime.now(GuildService.TOKYO)));
    model.addAttribute("newcomers", guild.characters(0).stream().limit(1).toList());
    model.addAttribute("canonical", properties.publicUrl());
    return "guild";
  }

  @GetMapping("/guild/characters")
  public String characters(Model model, @RequestParam(defaultValue = "0") int page) {
    int safePage = Math.max(0, Math.min(page, 10000));
    model.addAttribute("characters", guild.characters(safePage));
    model.addAttribute("page", safePage);
    return "guild-characters";
  }

  @GetMapping("/guild/characters/{id}")
  public String character(Model model, @PathVariable long id) {
    model.addAttribute("character", guild.character(id));
    return "guild-character";
  }
}
