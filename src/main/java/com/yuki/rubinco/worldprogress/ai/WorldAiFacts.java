package com.yuki.rubinco.worldprogress.ai;

import java.util.Optional;

/** Statistics a site hands to the AI. Only facts members can already see on the site. */
public interface WorldAiFacts {
  String gameId();

  /** Shown as the post's author, e.g. "Octarine観測AI". */
  String actorName();

  /** Empty while the world is unpublished or not imported yet. */
  Optional<String> facts();
}
