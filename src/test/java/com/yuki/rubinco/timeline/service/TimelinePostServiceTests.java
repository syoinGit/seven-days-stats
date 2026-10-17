package com.yuki.rubinco.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.service.CurrentWebAccountService;
import com.yuki.rubinco.timeline.entity.ReactionType;
import com.yuki.rubinco.timeline.entity.T_TimelinePost;
import com.yuki.rubinco.timeline.entity.T_TimelinePostReaction;
import com.yuki.rubinco.timeline.repository.T_TimelinePostReactionRepository;
import com.yuki.rubinco.timeline.repository.T_TimelinePostRepository;
import com.yuki.rubinco.watchpoint.persistence.repository.M_PlayerRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class TimelinePostServiceTests {
  private final T_TimelinePostRepository posts = mock(T_TimelinePostRepository.class);
  private final T_TimelinePostReactionRepository reactions = mock(T_TimelinePostReactionRepository.class);
  private final CurrentWebAccountService accounts = mock(CurrentWebAccountService.class);
  private final TimelinePostService service = new TimelinePostService(posts, reactions,
      mock(M_PlayerRepository.class), accounts, mock(TimelineMessageFactory.class));

  @Test
  void readsReactionsOncePerPageAndKeepsCountsAndCurrentAccountSeparate() {
    var account = new M_WebAccount();
    account.setId(10L);
    when(accounts.current(null)).thenReturn(Optional.of(account));
    when(posts.findVisibleFeed(eq("7dtd"), any()))
        .thenReturn(new PageImpl<>(List.of(post(1L), post(2L))));
    when(reactions.findAllByTimelinePostIdIn(List.of(1L, 2L)))
        .thenReturn(List.of(reaction(1L, 10L, "NICE"), reaction(1L, 20L, "NICE"),
            reaction(2L, 20L, "NICE"), reaction(2L, 30L, "UNKNOWN_LEGACY_TYPE")));

    var page = service.feed(null, 0);

    assertThat(page.posts().get(0).reactions().get(ReactionType.NICE)).isEqualTo(2);
    assertThat(page.posts().get(0).currentReaction()).isEqualTo("NICE");
    assertThat(page.posts().get(0).ownPost()).isTrue();
    assertThat(page.posts().get(1).reactions().get(ReactionType.NICE)).isEqualTo(1);
    assertThat(page.posts().get(1).currentReaction()).isNull();
    verify(reactions).findAllByTimelinePostIdIn(List.of(1L, 2L));
    verifyNoMoreInteractions(reactions);
  }

  @Test
  void pagesBeyondSixHundredDoNotRepeatTheSamePosts() {
    var pageable = PageRequest.of(51, TimelinePostService.PAGE_SIZE);
    when(posts.findVisibleFeed("7dtd", pageable))
        .thenReturn(new PageImpl<>(List.of(post(613L)), pageable, 613));
    when(accounts.current(null)).thenReturn(Optional.empty());
    when(reactions.findAllByTimelinePostIdIn(List.of(613L))).thenReturn(List.of());

    var page = service.feed(null, 612);

    assertThat(page.posts()).extracting(TimelinePostService.PostView::id).containsExactly(613L);
    assertThat(page.nextOffset()).isEqualTo(613);
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  void emptyPagesDoNotQueryReactions() {
    when(posts.findVisibleFeed(eq("7dtd"), any()))
        .thenReturn(new PageImpl<>(List.of()));
    when(accounts.current(null)).thenReturn(Optional.empty());

    assertThat(service.feed(null, -1).posts()).isEmpty();

    verifyNoInteractions(reactions);
  }

  private T_TimelinePost post(Long id) {
    var post = new T_TimelinePost();
    post.setId(id);
    post.setAuthorAccountId(10L);
    post.setPostType("PLAYER_MESSAGE");
    post.setMessage("投稿");
    post.setPublishedAt(OffsetDateTime.parse("2026-10-16T00:00:00Z"));
    return post;
  }

  private T_TimelinePostReaction reaction(Long postId, Long accountId, String type) {
    var reaction = new T_TimelinePostReaction();
    reaction.setTimelinePostId(postId);
    reaction.setAccountId(accountId);
    reaction.setReactionType(type);
    return reaction;
  }
}
