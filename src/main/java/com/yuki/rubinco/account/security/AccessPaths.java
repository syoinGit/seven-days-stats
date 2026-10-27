package com.yuki.rubinco.account.security;

/**
 * Paths both sign-in setups (local form login and Cognito) treat alike, so a new page is added in one place. Which roles
 * may use them is still decided by each setup.
 */
final class AccessPaths {
  /** The guild anyone may read, signed in or not. */
  static final String[] PUBLIC_GUILD = {"/", "/guild", "/guild/characters/*", "/guild/log", "/guild/log/*", "/guild/pairs/*",
      "/guild/dungeon", "/guild/dungeon/*", "/guild/towns/*", "/guild/talks/*/choices/*", "/guild/neighbor", "/guild/meal",
      "/guild/towns/*/meal"};
  /** Maintenance pages for admins only (AdminAccess). */
  static final String[] ADMIN_MAINTENANCE = {"/guild/maintenance", "/guild/maintenance/**", "/octarine/maintenance/**",
      "/luminite/maintenance/**"};
  static final String[] SITE_MAINTENANCE = {"/maintenance/**", "/portal/maintenance/**"};
  static final String[] PORTAL_SERVER_ACTIONS = {"/portal/actions/games/*/start", "/portal/actions/games/*/stop"};
  /** What a signed-in player posts: their status and timeline posts. */
  static final String[] PLAYER_POSTS = {"/players/*/status", "/posts", "/posts/*/react", "/posts/*/delete"};
  static final String[] STATIC = {"/css/**", "/img/**", "/js/**", "/favicon.ico", "/error"};

  private AccessPaths() { }
}
