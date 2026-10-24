package com.yuki.rubinco.luminite.web;

import com.yuki.rubinco.luminite.ingest.TerrariaImportService;
import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.luminite.ingest.TerrariaWorldParser;
import com.yuki.rubinco.worldprogress.service.WorldImportRunLog;
import com.yuki.rubinco.worldprogress.service.WorldProgressAdminService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/luminite/maintenance")
@RequiredArgsConstructor
@PreAuthorize("@adminAccess.currentUserIsAdmin()")
public class LuminiteMaintenanceController {
  private static final String GAME_ID = TerrariaImportService.GAME_ID;
  private static final String REDIRECT = "redirect:/luminite/maintenance";
  private final WorldProgressAdminService admin;
  private final WorldImportRunLog runLog;
  private final TerrariaNames names;

  @GetMapping
  public String index(Model model, @RequestParam(defaultValue = "0") int page) {
    model.addAttribute("runs", admin.runs(GAME_ID, Math.max(0, Math.min(page, 100000))));
    model.addAttribute("worlds", admin.worlds(GAME_ID));
    model.addAttribute("definitions", admin.definitions(GAME_ID));
    model.addAttribute("articles", admin.articles(GAME_ID));
    model.addAttribute("conditions", WorldProgressAdminService.CONDITIONS);
    model.addAttribute("parserVersion", TerrariaWorldParser.PARSER_VERSION);
    model.addAttribute("containerVersion", TerrariaWorldParser.SUPPORTED_VERSION);
    model.addAttribute("namesLoaded", names.loaded());
    model.addAttribute("namesItemCount", names.itemCount());
    return "luminite-maintenance";
  }

  @PostMapping("/import")
  public String requestImport(Authentication authentication, RedirectAttributes redirect) {
    boolean queued = runLog.queueManual(GAME_ID, authentication.getName()).isPresent();
    redirect.addFlashAttribute(queued ? "notice" : "error", queued
        ? "再取得を登録しました。15秒ほどで実行されます。"
        : "実行待ち・実行中の取り込みがあるため、登録しませんでした。");
    return REDIRECT;
  }

  @PostMapping("/worlds/{worldId}")
  public String updateWorld(@PathVariable Long worldId, @RequestParam String displayName,
      @RequestParam(required = false) String configuredNote,
      @RequestParam(defaultValue = "false") boolean published, RedirectAttributes redirect) {
    return run(redirect, "ワールド設定を保存しました。",
        () -> admin.updateWorld(GAME_ID, worldId, displayName, configuredNote, published));
  }

  @PostMapping("/worlds/new-generation")
  public String newGeneration(@RequestParam String displayName,
      @RequestParam(required = false) String configuredNote,
      @RequestParam(defaultValue = "false") boolean confirmed, RedirectAttributes redirect) {
    if (!confirmed) {
      redirect.addFlashAttribute("error", "世代の切り替えを確認してください。");
      return REDIRECT;
    }
    return run(redirect, "新しいワールド世代に切り替えました。以後の取り込みは新しい履歴になります。",
        () -> admin.startNewGeneration(GAME_ID, displayName, configuredNote));
  }

  @PostMapping("/worlds/{worldId}/activate")
  public String activate(@PathVariable Long worldId, RedirectAttributes redirect) {
    return run(redirect, "有効なワールド世代を切り替えました。", () -> admin.activate(GAME_ID, worldId));
  }

  @PostMapping("/definitions/{definitionId}")
  public String updateDefinition(@PathVariable Long definitionId, @RequestParam String displayName,
      @RequestParam(required = false) String description,
      @RequestParam(defaultValue = "false") boolean spoiler, RedirectAttributes redirect) {
    return run(redirect, "進捗項目を保存しました。",
        () -> admin.updateDefinition(GAME_ID, definitionId, displayName, description, spoiler));
  }

  @PostMapping({"/articles", "/articles/{articleId}"})
  public String saveArticle(@PathVariable(required = false) Long articleId,
      @RequestParam String title, @RequestParam String body,
      @RequestParam(required = false) String gameVersion,
      @RequestParam(required = false) String referenceUrl,
      @RequestParam(required = false) String referenceLabel,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate verifiedOn,
      @RequestParam(defaultValue = "false") boolean spoiler,
      @RequestParam(defaultValue = "false") boolean published,
      @RequestParam(defaultValue = "0") int sortOrder,
      @RequestParam Map<String, String> parameters, RedirectAttributes redirect) {
    Map<Long, String> conditions = new HashMap<>();
    parameters.forEach((name, value) -> {
      if (name.startsWith("condition_")) {
        try {
          conditions.put(Long.valueOf(name.substring("condition_".length())), value);
        } catch (NumberFormatException ignored) {
          // Unknown form fields are ignored.
        }
      }
    });
    return run(redirect, "攻略記事を保存しました。", () -> admin.saveArticle(GAME_ID, articleId,
        new WorldProgressAdminService.ArticleForm(title, body, gameVersion, referenceUrl,
            referenceLabel, verifiedOn, spoiler, published, sortOrder, conditions)));
  }

  @PostMapping("/articles/{articleId}/delete")
  public String deleteArticle(@PathVariable Long articleId, RedirectAttributes redirect) {
    return run(redirect, "攻略記事を削除しました。", () -> admin.deleteArticle(GAME_ID, articleId));
  }

  private String run(RedirectAttributes redirect, String notice, Runnable action) {
    try {
      action.run();
      redirect.addFlashAttribute("notice", notice);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return REDIRECT;
  }
}
