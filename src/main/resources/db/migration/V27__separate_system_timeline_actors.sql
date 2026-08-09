-- Connection activity belongs to the monitor account. The raw join/leave tables retain player IDs.
UPDATE T_TIMELINE_POST
SET actor_name = 'CONNECTION MONITOR', actor_player_id = NULL
WHERE post_type IN ('LOGIN', 'LOGOUT');

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
