package com.yuki.rubinco.watchpoint.web;

import com.yuki.rubinco.account.service.CurrentWebAccountService;
import com.yuki.rubinco.timeline.service.TimelinePostService;
import com.yuki.rubinco.watchpoint.player.PlayerStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class PlayerInteractionController {

  private final PlayerStatusService playerStatusService;
  private final CurrentWebAccountService currentAccountService;
  private final TimelinePostService timelinePostService;

  @PostMapping("/players/{playerId}/status")
  public String updateStatus(
      @PathVariable Long playerId,
      @RequestParam String status,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var updated = currentAccountService.current(authentication)
        .filter(account -> playerId.equals(account.getPlayerId()))
        .flatMap(account -> playerStatusService.updateByPlayerId(playerId, status, "WEB"));
    redirectAttributes.addFlashAttribute(updated.isPresent() ? "notice" : "error",
        updated.isPresent() ? "ステータスを更新しました。" : "自分のオンライン中プレイヤーだけ更新できます。");
    return "redirect:/players/" + playerId;
  }

  @GetMapping("/community")
  public String community() {
    return "redirect:/dashboard#timeline";
  }

  @PostMapping("/posts")
  public String createPost(
      @RequestParam String body,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var result = timelinePostService.createPlayerPost(authentication, body);
    redirectAttributes.addFlashAttribute(result.success() ? "notice" : "error", result.message());
    return "redirect:/dashboard#timeline";
  }

  @PostMapping("/posts/{postId}/react")
  public String toggleReaction(
      @PathVariable Long postId,
      @RequestParam String reaction,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var result = timelinePostService.toggleReaction(authentication, postId, reaction);
    redirectAttributes.addFlashAttribute(result.success() ? "notice" : "error", result.message());
    return "redirect:/dashboard#timeline";
  }

  @PostMapping("/posts/{postId}/delete")
  public String deletePost(
      @PathVariable Long postId,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    var result = timelinePostService.deletePlayerPost(authentication, postId);
    redirectAttributes.addFlashAttribute(result.success() ? "notice" : "error", result.message());
    return "redirect:/dashboard#timeline";
  }

}
