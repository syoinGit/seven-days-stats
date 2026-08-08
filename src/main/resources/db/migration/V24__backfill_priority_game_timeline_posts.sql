-- Seed the new feed with high-signal history. High-volume combat/sleeper rows intentionally start
-- using the runtime weighted selector, rather than turning the first deployment into log spam.
INSERT INTO T_TIMELINE_POST (
    published_at, post_type, actor_type, actor_player_id, actor_name, message, coordinate,
    source_type, source_id, source_hash, priority, created_at)
SELECT j.occurred_at, 'LOGIN', 'GAME', j.player_id, j.player_name,
       '監視記録。' || j.player_name || 'がログインした。 生存信号を確認。',
       CAST(j.position_x AS VARCHAR) || ', ' || CAST(j.position_y AS VARCHAR) || ', ' || CAST(j.position_z AS VARCHAR),
       'PLAYER_JOIN', j.player_join_transaction_id, 'PLAYER_JOIN:' || j.source_log_hash, 100, j.created_at
FROM T_PLAYER_JOIN_TRANSACTION j
WHERE NOT EXISTS (
    SELECT 1 FROM T_TIMELINE_POST tp WHERE tp.source_hash = 'PLAYER_JOIN:' || j.source_log_hash);

INSERT INTO T_TIMELINE_POST (
    published_at, post_type, actor_type, actor_player_id, actor_name, message, coordinate,
    source_type, source_id, source_hash, priority, created_at)
SELECT l.occurred_at, 'LOGOUT', 'GAME', l.player_id, l.player_name,
       l.player_name || 'がログアウトした。 無事な帰還を記録。', NULL,
       'PLAYER_LEAVE', l.player_leave_transaction_id, 'PLAYER_LEAVE:' || l.source_log_hash, 100, l.created_at
FROM T_PLAYER_LEAVE_TRANSACTION l
WHERE NOT EXISTS (
    SELECT 1 FROM T_TIMELINE_POST tp WHERE tp.source_hash = 'PLAYER_LEAVE:' || l.source_log_hash);

INSERT INTO T_TIMELINE_POST (
    published_at, post_type, actor_type, actor_player_id, actor_name, message, coordinate,
    source_type, source_id, source_hash, priority, created_at)
SELECT w.occurred_at,
       CASE WHEN w.event_type = 'PLAYER_DEATH' THEN 'PLAYER_DEATH' ELSE 'WORLD_EVENT' END,
       'GAME', w.player_id, COALESCE(w.actor_player_name, 'WATCHPOINT'),
       CASE w.event_type
         WHEN 'AIR_DROP' THEN '補給物資が投下された。 荒野は今日も脚本を読んでいない。'
         WHEN 'WANDERING_HORDE' THEN '徘徊ホードが発生した。カメラを回せ。'
         WHEN 'SCOUT_HORDE' THEN 'スクリーマーの気配がした。避難計画を再確認。'
         WHEN 'SCREAMER_SPAWN' THEN 'スクリーマーが出現した。静寂は長続きしない。'
         WHEN 'BLOOD_MOON' THEN 'ブラッドムーン予定が更新された。備えがあれば少しだけ安心。'
         WHEN 'PLAYER_DEATH' THEN COALESCE(w.actor_player_name, '誰か') || 'が力尽きた。次のテイクではもっと安全に。'
         ELSE COALESCE(w.detail_text, '世界でイベントが発生した。')
       END,
       CAST(w.position_x AS VARCHAR) || ', ' || CAST(w.position_y AS VARCHAR) || ', ' || CAST(w.position_z AS VARCHAR),
       'WORLD_EVENT', w.world_event_transaction_id, 'WORLD_EVENT:' || w.source_log_hash, 82, w.created_at
FROM T_WORLD_EVENT_TRANSACTION w
WHERE NOT EXISTS (
    SELECT 1 FROM T_TIMELINE_POST tp WHERE tp.source_hash = 'WORLD_EVENT:' || w.source_log_hash);
