ALTER TABLE M_WEB_ACCOUNT ADD COLUMN cognito_sub VARCHAR(80);
CREATE UNIQUE INDEX uq_m_web_account_cognito_sub
  ON M_WEB_ACCOUNT (cognito_sub);

ALTER TABLE T_TIMELINE_POST ADD COLUMN event_id VARCHAR(100);
ALTER TABLE T_TIMELINE_POST ADD COLUMN title VARCHAR(160);
ALTER TABLE T_TIMELINE_POST ADD COLUMN progress INTEGER;
ALTER TABLE T_TIMELINE_POST ADD COLUMN stage_name VARCHAR(160);
ALTER TABLE T_TIMELINE_POST ADD COLUMN creator_sub VARCHAR(80);
ALTER TABLE T_TIMELINE_POST ADD COLUMN metadata_json TEXT;
ALTER TABLE T_TIMELINE_POST ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

UPDATE T_TIMELINE_POST
SET event_id = source_hash,
    title = CASE
      WHEN post_type = 'PLAYER_MESSAGE' THEN '生存者メッセージ'
      WHEN post_type = 'WATCHPOINT' THEN 'WATCHPOINT観測'
      ELSE post_type
    END,
    metadata_json = '{}',
    updated_at = created_at
WHERE event_id IS NULL;

-- event_id intentionally remains nullable during the expand/contract migration window.
-- A rolled-back Watchpoint binary does not know this column; the new application falls back to
-- the already unique source_hash for any row written by that binary.
ALTER TABLE T_TIMELINE_POST ALTER COLUMN title SET DEFAULT 'Adventure event';
ALTER TABLE T_TIMELINE_POST ALTER COLUMN title SET NOT NULL;
ALTER TABLE T_TIMELINE_POST ALTER COLUMN metadata_json SET NOT NULL;
ALTER TABLE T_TIMELINE_POST ALTER COLUMN metadata_json SET DEFAULT '{}';
ALTER TABLE T_TIMELINE_POST ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE T_TIMELINE_POST ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE T_TIMELINE_POST ADD CONSTRAINT ck_t_timeline_post_progress
  CHECK (progress IS NULL OR (progress >= 0 AND progress <= 100));
CREATE UNIQUE INDEX uq_t_timeline_post_event_id ON T_TIMELINE_POST (event_id);

CREATE TABLE M_GAME_PORTAL_STATE (
    game_id VARCHAR(40) PRIMARY KEY,
    server_status VARCHAR(20) NOT NULL,
    progress INTEGER NOT NULL,
    stage_name VARCHAR(160) NOT NULL,
    schedule_text VARCHAR(200) NOT NULL,
    game_version VARCHAR(80) NOT NULL,
    connection_text VARCHAR(240),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_m_game_portal_state_status CHECK (
      server_status IN ('offline', 'starting', 'online', 'maintenance', 'error')
    ),
    CONSTRAINT ck_m_game_portal_state_progress CHECK (progress >= 0 AND progress <= 100)
);

INSERT INTO M_GAME_PORTAL_STATE (
  game_id, server_status, progress, stage_name, schedule_text, game_version, connection_text, updated_at
) VALUES
  ('7dtd', 'offline', 18, '第1章・荒野の観測', '毎日 18:00–23:30 JST', 'Alpha 22', NULL, CURRENT_TIMESTAMP),
  ('terraria', 'offline', 42, '星明かりの地下層', '週末 20:00–23:30 JST', '1.4.4', NULL, CURRENT_TIMESTAMP);
