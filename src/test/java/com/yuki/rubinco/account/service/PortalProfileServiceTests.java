package com.yuki.rubinco.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.yuki.rubinco.account.entity.M_WebAccount;
import com.yuki.rubinco.account.repository.M_WebAccountRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortalProfileServiceTests {
  @Test
  void playerAndPortalAuthorShareTheirIconWhileGuestViewsStayAnonymous() {
    var repository = mock(M_WebAccountRepository.class);
    var service = new PortalProfileService(repository, mock(CurrentWebAccountService.class));
    var account = new M_WebAccount();
    account.setId(17L);
    account.setLoginId("explorer");
    account.setDisplayName("Explorer");
    account.setPlayerId(42L);
    account.setCognitoSub("user-sub");
    account.setAvatarKey("leaf");
    when(repository.findAllByOrderByLoginIdAsc()).thenReturn(List.of(account));
    var directory = service.directory();
    assertThat(service.avatar(42L, null, directory, false)).isEqualTo("/img/avatars/leaf.svg");
    assertThat(service.avatar(null, "user-sub", directory, false)).isEqualTo("/img/avatars/leaf.svg");
    assertThat(service.avatar(null, "local:explorer", directory, false)).isEqualTo("/img/avatars/leaf.svg");
    assertThat(service.avatar(42L, "user-sub", directory, true)).isEqualTo("/img/avatars/guest.svg");
    assertThat(service.authorName(42L, null, "Game name", directory, false)).isEqualTo("Explorer");
    assertThat(service.authorName(42L, null, "Game name", directory, true)).isEqualTo("プレイヤー");
    account.setAvatarKey(null);
    assertThat(service.profile(account).avatarKey()).isIn(PortalProfileService.AVATARS);
    account.setAvatarKey("../../invalid");
    assertThat(service.profile(account).avatarKey()).isIn(PortalProfileService.AVATARS);
  }
}
