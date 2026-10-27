package com.yuki.rubinco.worldprogress.web;

import com.yuki.rubinco.worldprogress.service.WorldImportRunLog;
import com.yuki.rubinco.worldprogress.service.WorldProgressAdminService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A world game's maintenance page (Luminite, Octarine): import runs, world generations, progress definitions and guide
 * articles. Each game's controller maps it under its own path and adds what its parser and names say.
 */
@PreAuthorize("@adminAccess.currentUserIsAdmin()")
public abstract class WorldMaintenanceController {
  private final String gameId;
  private final String view;
  private final String back;
  private final WorldProgressAdminService admin;
  private final WorldImportRunLog runLog;

  /** path: where the page is ("/luminite/maintenance"); view: its template. */
  protected WorldMaintenanceController(String gameId, String path, String view, WorldProgressAdminService admin,
      WorldImportRunLog runLog) {
    this.gameId = gameId;
    this.view = view;
    this.back = "redirect:" + path;
    this.admin = admin;
    this.runLog = runLog;
  }

  /** The parser's versions and how many names are loaded, for the page. */
  protected abstract void describeParser(Model model);

  @GetMapping
  public String index(Model model, @RequestParam(defaultValue = "0") int page) {
    model.addAttribute("runs", admin.runs(gameId, Math.max(0, Math.min(page, 100000))));
    model.addAttribute("worlds", admin.worlds(gameId));
    model.addAttribute("definitions", admin.definitions(gameId));
    model.addAttribute("articles", admin.articles(gameId));
    model.addAttribute("conditions", WorldProgressAdminService.CONDITIONS);
    describeParser(model);
    return view;
  }

  @PostMapping("/import")
  public String requestImport(Authentication authentication, RedirectAttributes redirect) {
    boolean queued = runLog.queueManual(gameId, authentication.getName()).isPresent();
    redirect.addFlashAttribute(queued ? "notice" : "error", queued
        ? "再取得を登録しました。15秒ほどで実行されます。"
        : "実行待ち・実行中の取り込みがあるため、登録しませんでした。");
    return back;
  }

  @PostMapping("/worlds/{worldId}")
  public String updateWorld(@PathVariable Long worldId, @RequestParam String displayName,
      @RequestParam(required = false) String configuredNote,
      @RequestParam(defaultValue = "false") boolean published, RedirectAttributes redirect) {
    return run(redirect, "ワールド設定を保存しました。",
        () -> admin.updateWorld(gameId, worldId, displayName, configuredNote, published));
  }

  @PostMapping("/worlds/new-generation")
  public String newGeneration(@RequestParam String displayName,
      @RequestParam(required = false) String configuredNote,
      @RequestParam(defaultValue = "false") boolean confirmed, RedirectAttributes redirect) {
    if (!confirmed) {
      redirect.addFlashAttribute("error", "世代の切り替えを確認してください。");
      return back;
    }
    return run(redirect, "新しいワールド世代に切り替えました。以後の取り込みは新しい履歴になります。",
        () -> admin.startNewGeneration(gameId, displayName, configuredNote));
  }

  @PostMapping("/worlds/{worldId}/activate")
  public String activate(@PathVariable Long worldId, RedirectAttributes redirect) {
    return run(redirect, "有効なワールド世代を切り替えました。", () -> admin.activate(gameId, worldId));
  }

  @PostMapping("/definitions/{definitionId}")
  public String updateDefinition(@PathVariable Long definitionId, @RequestParam String displayName,
      @RequestParam(required = false) String description,
      @RequestParam(defaultValue = "false") boolean spoiler, RedirectAttributes redirect) {
    return run(redirect, "進捗項目を保存しました。",
        () -> admin.updateDefinition(gameId, definitionId, displayName, description, spoiler));
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
    return run(redirect, "攻略記事を保存しました。", () -> admin.saveArticle(gameId, articleId,
        new WorldProgressAdminService.ArticleForm(title, body, gameVersion, referenceUrl,
            referenceLabel, verifiedOn, spoiler, published, sortOrder, conditions)));
  }

  @PostMapping("/articles/{articleId}/delete")
  public String deleteArticle(@PathVariable Long articleId, RedirectAttributes redirect) {
    return run(redirect, "攻略記事を削除しました。", () -> admin.deleteArticle(gameId, articleId));
  }

  private String run(RedirectAttributes redirect, String notice, Runnable action) {
    try {
      action.run();
      redirect.addFlashAttribute("notice", notice);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return back;
  }
}
