package com.yuki.sevendays_states.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuki.sevendays_states.entity.M_Player;
import com.yuki.sevendays_states.entity.M_WebAccount;
import com.yuki.sevendays_states.repository.M_PlayerRepository;
import com.yuki.sevendays_states.repository.M_WebAccountRepository;
import com.yuki.sevendays_states.repository.T_PlayerPostLikeRepository;
import com.yuki.sevendays_states.repository.T_PlayerPostRepository;
import com.yuki.sevendays_states.repository.T_TimelinePostReactionRepository;
import com.yuki.sevendays_states.repository.T_TimelinePostRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sevendays_states_social;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "app.sevendays.import.startup-enabled=false"
})
class PlayerSocialServiceTests {

  @Autowired
  private PlayerSocialService socialService;

  @Autowired
  private M_PlayerRepository playerRepository;

  @Autowired
  private M_WebAccountRepository accountRepository;

  @Autowired
  private T_PlayerPostRepository postRepository;

  @Autowired
  private T_PlayerPostLikeRepository likeRepository;

  @Autowired
  private T_TimelinePostRepository timelinePostRepository;

  @Autowired
  private T_TimelinePostReactionRepository timelineReactionRepository;

  private Authentication authentication;

  @BeforeEach
  void setUp() {
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
    assertThat(socialService.createPost(authentication, "荒野からこんにちは").success()).isTrue();
    assertThat(socialService.feed(authentication)).singleElement()
        .satisfies(post -> {
          assertThat(post.playerName()).isEqualTo("交流プレイヤー");
          assertThat(post.likeCount()).isZero();
        });

    Long postId = socialService.feed(authentication).getFirst().id();
    assertThat(socialService.toggleLike(authentication, postId).success()).isTrue();
    assertThat(socialService.feed(authentication).getFirst().likeCount()).isEqualTo(1);
    assertThat(socialService.toggleLike(authentication, postId).success()).isTrue();
    assertThat(socialService.feed(authentication).getFirst().likeCount()).isZero();
  }

  @Test
  void postOwnerCanDeletePostAndItsLikes() {
    socialService.createPost(authentication, "削除する投稿");
    Long postId = socialService.feed(authentication).getFirst().id();
    socialService.toggleLike(authentication, postId);

    assertThat(socialService.deletePost(authentication, postId).success()).isTrue();
    assertThat(timelinePostRepository.existsById(postId)).isFalse();
    assertThat(timelineReactionRepository.findAllByTimelinePostId(postId)).isEmpty();
  }

  @Test
  void rejectsPostsLongerThanOneHundredCharacters() {
    assertThat(socialService.createPost(authentication, "あ".repeat(101)))
        .satisfies(result -> {
          assertThat(result.success()).isFalse();
          assertThat(result.message()).contains("100文字以内");
        });
  }
}
