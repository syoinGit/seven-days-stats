INSERT INTO M_GAME_PORTAL_STATE (
  game_id, server_status, progress, stage_name, schedule_text, game_version, connection_text, updated_at
) VALUES
  ('corekeeper', 'offline', 0, '冒険の記録を待っています', 'サイトから起動', 'サーバー設定に準拠', NULL, CURRENT_TIMESTAMP),
  ('minecraft', 'offline', 0, '冒険の記録を待っています', 'サイトから起動', 'サーバー設定に準拠', NULL, CURRENT_TIMESTAMP);

-- Replace only the old shipped schedules, preserving any operator-customized values.
UPDATE M_GAME_PORTAL_STATE SET schedule_text = 'サイトから起動'
WHERE game_id = '7dtd' AND schedule_text = '毎日 18:00–23:30 JST';

UPDATE M_GAME_PORTAL_STATE SET schedule_text = 'EC2起動時に自動起動'
WHERE game_id = 'terraria' AND schedule_text = '週末 20:00–23:30 JST';
