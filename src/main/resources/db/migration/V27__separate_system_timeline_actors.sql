-- Connection activity is authored by the player, while the event text remains system-generated.
UPDATE T_TIMELINE_POST post
SET actor_name = (
      SELECT joined.player_name FROM T_PLAYER_JOIN_TRANSACTION joined
      WHERE joined.player_join_transaction_id = post.source_id
    ),
    actor_player_id = (
      SELECT joined.player_id FROM T_PLAYER_JOIN_TRANSACTION joined
      WHERE joined.player_join_transaction_id = post.source_id
    )
WHERE post.post_type = 'LOGIN' AND post.source_type = 'PLAYER_JOIN'
  AND EXISTS (
    SELECT 1 FROM T_PLAYER_JOIN_TRANSACTION joined
    WHERE joined.player_join_transaction_id = post.source_id
  );

UPDATE T_TIMELINE_POST post
SET actor_name = (
      SELECT left_game.player_name FROM T_PLAYER_LEAVE_TRANSACTION left_game
      WHERE left_game.player_leave_transaction_id = post.source_id
    ),
    actor_player_id = (
      SELECT left_game.player_id FROM T_PLAYER_LEAVE_TRANSACTION left_game
      WHERE left_game.player_leave_transaction_id = post.source_id
    )
WHERE post.post_type = 'LOGOUT' AND post.source_type = 'PLAYER_LEAVE'
  AND EXISTS (
    SELECT 1 FROM T_PLAYER_LEAVE_TRANSACTION left_game
    WHERE left_game.player_leave_transaction_id = post.source_id
  );

-- Blood moon alerts are system announcements, including posts created before dedicated actors existed.
UPDATE T_TIMELINE_POST
SET actor_name = 'BLOOD MOON ALERT', actor_player_id = NULL
WHERE post_type = 'BLOOD_MOON';

-- Split the former WORLD_EVENT bucket using its immutable source transaction.
UPDATE T_TIMELINE_POST post
SET post_type = 'AIR_DROP', actor_name = 'WORLD INTEL', actor_player_id = NULL
WHERE post.source_type = 'WORLD_EVENT'
  AND EXISTS (
    SELECT 1 FROM T_WORLD_EVENT_TRANSACTION event
    WHERE event.world_event_transaction_id = post.source_id AND event.event_type = 'AIR_DROP'
  );

UPDATE T_TIMELINE_POST post
SET post_type = 'HORDE_ALERT', actor_name = 'HORDE WATCH', actor_player_id = NULL
WHERE post.source_type = 'WORLD_EVENT'
  AND EXISTS (
    SELECT 1 FROM T_WORLD_EVENT_TRANSACTION event
    WHERE event.world_event_transaction_id = post.source_id
      AND event.event_type IN ('WANDERING_HORDE', 'SCOUT_HORDE', 'SCREAMER_SPAWN')
  );

UPDATE T_TIMELINE_POST post
SET message = (
  SELECT CASE event.event_type
    WHEN 'WANDERING_HORDE' THEN COALESCE(event.actor_player_name, '観測地点') || 'の近くで徘徊ホードが発生した！'
    WHEN 'SCOUT_HORDE' THEN COALESCE(event.actor_player_name, '観測地点') || 'の近くでスクリーマーの群れを観測した！'
    WHEN 'SCREAMER_SPAWN' THEN COALESCE(event.actor_player_name, '観測地点') || 'の近くでスクリーマーが出現した！'
    ELSE post.message
  END
  FROM T_WORLD_EVENT_TRANSACTION event
  WHERE event.world_event_transaction_id = post.source_id
)
WHERE post.post_type = 'HORDE_ALERT'
  AND post.source_type = 'WORLD_EVENT';

UPDATE T_TIMELINE_POST
SET actor_name = 'WORLD INTEL', actor_player_id = NULL
WHERE post_type = 'WORLD_EVENT';
