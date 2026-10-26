package com.yuki.rubinco.worldprogress.ai;

import java.time.OffsetDateTime;
import java.util.Optional;

/** A game server the AI can murmur about. Only servers with someone connected are picked. */
public interface AiMurmurSource {
  String gameId();

  /** Off when the game's own AI switch is off. */
  boolean enabled();

  /** Players connected right now; zero keeps this server out of the draw. */
  int onlineCount();

  /** When this server's last AI murmur was posted. */
  Optional<OffsetDateTime> lastPublished();

  /** Writes and saves one murmur. NO_DATA when there is nothing to talk about, so another server is tried. */
  Outcome murmur();

  enum Outcome { PUBLISHED, NO_DATA, FAILED }
}
