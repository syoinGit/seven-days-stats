package com.yuki.rubinco.worldprogress.ai;

import java.util.Optional;

/** Statistics a site hands to the AI. Only facts members can already see on the site. */
public interface WorldAiFacts {
  String gameId();

  /** The game's own name, so the AI knows what it is looking at, e.g. "Core Keeper". */
  String gameTitle();

  /** Empty while the world is unpublished or not imported yet. */
  Optional<String> facts();
}
