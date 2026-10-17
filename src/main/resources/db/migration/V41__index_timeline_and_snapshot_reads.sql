-- Match the equality predicates before the ordered feed/cooldown scan.
CREATE INDEX idx_t_timeline_post_game_visible_published
  ON T_TIMELINE_POST(game_id, visible, published_at DESC, timeline_post_id DESC);
CREATE INDEX idx_t_timeline_post_game_type_published
  ON T_TIMELINE_POST(game_id, post_type, published_at DESC);

-- Latest snapshot per player needs both chronological order and a stable ID tie-breaker.
CREATE INDEX idx_t_player_snapshot_player_latest
  ON T_PLAYER_STATE_SNAPSHOT(player_id, captured_at DESC, id DESC);
