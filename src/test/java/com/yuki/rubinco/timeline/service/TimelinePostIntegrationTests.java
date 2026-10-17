package com.yuki.rubinco.timeline.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import com.yuki.rubinco.timeline.entity.ReactionType;

import static org.assertj.core.api.Assertions.assertThat;
import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import com.yuki.rubinco.portal.service.GameTimelineService;
import com.yuki.rubinco.timeline.repository.T_TimelinePostReactionRepository;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.watchpoint.persistence.entity.M_Player;
import com.yuki.rubinco.watchpoint.persistence.repository.M_PlayerRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rubinco_social;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class TimelinePostIntegrationTests {

  @Autowired
  private TimelinePostService socialService;

  @Autowired
  private M_PlayerRepository playerRepository;

  @Autowired
  private M_WebAccountRepository accountRepository;

  @Autowired
  private T_TimelinePostRepository timelinePostRepository;

  @Autowired
  private T_TimelinePostReactionRepository timelineReactionRepository;

  @Autowired
  private GameTimelineService gameTimelineService;

  private Authentication authentication;

  @BeforeEach
  void setUp() {
    timelineReactionRepository.deleteAll();
    timelinePostRepository.deleteAll();

    String suffix = UUID.randomUUID().toString();
    M_Player player = new M_Player();
    player.setPlayerKey("EOS:social-player-" + suffix);
    player.setPlatform("EOS");
    player.setUserId("social-player-" + suffix);
    player.setPlayerName("交流プレイヤー");
    player = playerRepository.save(player);

    M_WebAccount account = new M_WebAccount();
    account.setLoginId("social-user-" + suffix);
    account.setPasswordHash("hash");
    account.setPlayerId(player.getId());
    account.setRole("PLAYER");
    account = accountRepository.save(account);
    authentication = UsernamePasswordAuthenticationToken.authenticated(
        account.getLoginId(), "", List.of());
  }

  @Test
  void authenticatedPlayerCanPostAndToggleLike() {
    assertThat(socialService.createPlayerPost(authentication, "荒野からこんにちは").success()).isTrue();
    assertThat(socialService.feed(authentication, 0).posts()).singleElement()
        .satisfies(post -> {
          assertThat(post.actor()).isEqualTo("交流プレイヤー");
          assertThat(post.reactions().get(ReactionType.NICE)).isZero();
        });

    Long postId = socialService.feed(authentication, 0).posts().getFirst().id();
    assertThat(socialService.toggleReaction(authentication, postId, "NICE").success()).isTrue();
    assertThat(socialService.feed(authentication, 0).posts().getFirst().reactions().get(ReactionType.NICE)).isEqualTo(1);
    assertThat(socialService.toggleReaction(authentication, postId, "NICE").success()).isTrue();
    assertThat(socialService.feed(authentication, 0).posts().getFirst().reactions().get(ReactionType.NICE)).isZero();
  }

  @Test
  void postOwnerCanDeletePostAndItsLikes() {
    socialService.createPlayerPost(authentication, "削除する投稿");
    Long postId = socialService.feed(authentication, 0).posts().getFirst().id();
    socialService.toggleReaction(authentication, postId, "NICE");

    assertThat(socialService.deletePlayerPost(authentication, postId).success()).isTrue();
    assertThat(timelinePostRepository.existsById(postId)).isFalse();
    assertThat(timelineReactionRepository.findAllByTimelinePostId(postId)).isEmpty();
  }

  @Test
  void accountWithoutLinkedPlayerCannotDeleteAnotherPlayersPost() {
    socialService.createPlayerPost(authentication, "所有者だけが消せる投稿");
    Long postId = socialService.feed(authentication, 0).posts().getFirst().id();
    M_WebAccount unlinked = new M_WebAccount();
    unlinked.setLoginId("unlinked-" + UUID.randomUUID());
    unlinked.setPasswordHash("hash");
    unlinked.setRole("PLAYER");
    unlinked = accountRepository.save(unlinked);
    Authentication unlinkedAuthentication = UsernamePasswordAuthenticationToken.authenticated(
        unlinked.getLoginId(), "", List.of());

    assertThat(socialService.deletePlayerPost(unlinkedAuthentication, postId))
        .satisfies(result -> {
          assertThat(result.success()).isFalse();
          assertThat(result.message()).contains("自分の投稿だけ");
        });
    assertThat(timelinePostRepository.existsById(postId)).isTrue();
  }

  @Test
  void rejectsPostsLongerThanOneHundredCharacters() {
    assertThat(socialService.createPlayerPost(authentication, "あ".repeat(101)))
        .satisfies(result -> {
          assertThat(result.success()).isFalse();
          assertThat(result.message()).contains("100文字以内");
        });
  }

  @Test
  void watchpointReactionsCannotMutateAnotherGamesTimeline() {
    var event = gameTimelineService.create("terraria", new GameTimelineService.EventInput(
        "PROGRESS", "Terraria event", "Terraria only", null, 50, "Hardmode", Map.of()),
        authentication);
    Long postId = timelinePostRepository.findByEventIdAndGameId(event.eventId(), "terraria")
        .orElseThrow()
        .getId();

    assertThat(socialService.toggleReaction(authentication, postId, "NICE"))
        .satisfies(result -> {
          assertThat(result.success()).isFalse();
          assertThat(result.message()).contains("見つかりません");
        });
    assertThat(timelineReactionRepository.findAllByTimelinePostId(postId)).isEmpty();
  }
  @Test
  void changingPlayerLinksDoesNotTransferPostOwnership() {
    socialService.createPlayerPost(authentication, "元のアカウントの投稿");
    Long postId = socialService.feed(authentication, 0).posts().getFirst().id();
    var author = accountRepository.findByLoginId(authentication.getName()).orElseThrow();
    Long playerId = author.getPlayerId();
    author.setPlayerId(null);
    accountRepository.saveAndFlush(author);
    var replacement = new M_WebAccount();
    replacement.setLoginId("replacement-" + UUID.randomUUID());
    replacement.setPasswordHash("hash");
    replacement.setPlayerId(playerId);
    accountRepository.saveAndFlush(replacement);
    var other = UsernamePasswordAuthenticationToken.authenticated(
        replacement.getLoginId(), "", List.of());

    assertThat(socialService.feed(other, 0).posts().getFirst().ownPost()).isFalse();
    assertThat(socialService.deletePlayerPost(other, postId).success()).isFalse();
    assertThat(socialService.feed(authentication, 0).posts().getFirst().ownPost()).isTrue();
    assertThat(socialService.deletePlayerPost(authentication, postId).success()).isTrue();
  }

  @Test
  void viewerCannotMutateTheFeedEvenWhenCallingTheServiceDirectly() {
    socialService.createPlayerPost(authentication, "変更できない投稿");
    Long postId = socialService.feed(authentication, 0).posts().getFirst().id();
    var account = accountRepository.findByLoginId(authentication.getName()).orElseThrow();
    account.setRole("VIEWER");
    accountRepository.saveAndFlush(account);

    assertThat(socialService.createPlayerPost(authentication, "拒否").success()).isFalse();
    assertThat(socialService.toggleReaction(authentication, postId, "NICE").success()).isFalse();
    assertThat(socialService.deletePlayerPost(authentication, postId).success()).isFalse();
    assertThat(timelinePostRepository.existsById(postId)).isTrue();
    assertThat(timelineReactionRepository.countByTimelinePostId(postId)).isZero();
  }

  @Test
  void simultaneousReactionTogglesAreSerializedInsteadOfViolatingTheUniqueConstraint() throws Exception {
    socialService.createPlayerPost(authentication, "同時操作");
    Long postId = socialService.feed(authentication, 0).posts().getFirst().id();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> toggle = () -> {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Start barrier timed out");
        }
        return socialService.toggleReaction(authentication, postId, "NICE").success();
      };
      var first = executor.submit(toggle);
      var second = executor.submit(toggle);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(timelineReactionRepository.countByTimelinePostId(postId)).isZero();
  }
}
