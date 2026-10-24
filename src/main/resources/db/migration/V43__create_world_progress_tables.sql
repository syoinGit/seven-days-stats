-- Game-neutral world progress tracking. Core Keeper (Octarine) is the first user.
-- Raw saves and game DLLs are never stored; JSON columns hold validated extraction output only.

CREATE TABLE M_GAME_WORLD (
    game_world_id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(40) NOT NULL,
    world_key VARCHAR(60) NOT NULL,
    generation INTEGER NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    -- Operator-entered facts (e.g. intended difficulty). Not verified from the save.
    configured_note VARCHAR(400),
    published BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    latest_snapshot_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_m_game_world_generation UNIQUE (game_id, world_key, generation),
    CONSTRAINT ck_m_game_world_generation CHECK (generation >= 1)
);

CREATE TABLE T_WORLD_SNAPSHOT (
    world_snapshot_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    source_sha256 VARCHAR(64) NOT NULL,
    container_version INTEGER,
    parser_version VARCHAR(40) NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_modified_at TIMESTAMP WITH TIME ZONE,
    parsed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    validation_status VARCHAR(20) NOT NULL,
    generation_json TEXT,
    extracted_json TEXT NOT NULL,
    CONSTRAINT uq_t_world_snapshot_source UNIQUE (game_world_id, source_sha256, parser_version)
);

ALTER TABLE M_GAME_WORLD ADD CONSTRAINT fk_m_game_world_latest_snapshot
  FOREIGN KEY (latest_snapshot_id) REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id);

CREATE TABLE T_WORLD_IMPORT_RUN (
    world_import_run_id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(40) NOT NULL,
    game_world_id BIGINT REFERENCES M_GAME_WORLD (game_world_id),
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_by VARCHAR(160),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    source_size BIGINT,
    source_modified_at TIMESTAMP WITH TIME ZONE,
    source_sha256 VARCHAR(64),
    decompressed_bytes BIGINT,
    container_version INTEGER,
    parser_version VARCHAR(40),
    world_snapshot_id BIGINT REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    error_code VARCHAR(40),
    error_message VARCHAR(500),
    CONSTRAINT ck_t_world_import_run_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_t_world_import_run_status CHECK (
      status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'UNCHANGED', 'FAILED'))
);

CREATE INDEX idx_t_world_import_run_game_requested
  ON T_WORLD_IMPORT_RUN (game_id, requested_at);
CREATE INDEX idx_t_world_import_run_game_status
  ON T_WORLD_IMPORT_RUN (game_id, status);

CREATE TABLE M_PROGRESS_DEFINITION (
    progress_definition_id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(40) NOT NULL,
    progress_key VARCHAR(60) NOT NULL,
    category VARCHAR(20) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    description VARCHAR(400),
    support_status VARCHAR(20) NOT NULL,
    source_kind VARCHAR(40) NOT NULL,
    source_object_id INTEGER,
    source_note VARCHAR(200),
    spoiler BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_m_progress_definition_key UNIQUE (game_id, progress_key),
    CONSTRAINT ck_m_progress_definition_support CHECK (support_status IN ('SUPPORTED', 'UNSUPPORTED'))
);

CREATE TABLE T_PROGRESS_STATE (
    progress_state_id BIGSERIAL PRIMARY KEY,
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    progress_definition_id BIGINT NOT NULL REFERENCES M_PROGRESS_DEFINITION (progress_definition_id),
    state VARCHAR(20) NOT NULL,
    CONSTRAINT uq_t_progress_state UNIQUE (world_snapshot_id, progress_definition_id),
    CONSTRAINT ck_t_progress_state CHECK (state IN ('ACHIEVED', 'NOT_ACHIEVED', 'UNKNOWN'))
);

CREATE TABLE T_PROGRESS_EVENT (
    progress_event_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    progress_definition_id BIGINT NOT NULL REFERENCES M_PROGRESS_DEFINITION (progress_definition_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    previous_state VARCHAR(20),
    new_state VARCHAR(20) NOT NULL,
    change_kind VARCHAR(20) NOT NULL,
    -- When this site first saw the change; never the in-game event time.
    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_t_progress_event_kind CHECK (
      change_kind IN ('INITIAL', 'CHANGED', 'REGRESSED', 'PARSER_CHANGE', 'NEWLY_SUPPORTED'))
);

CREATE INDEX idx_t_progress_event_world_observed
  ON T_PROGRESS_EVENT (game_world_id, first_observed_at);

CREATE TABLE M_GUIDE_ARTICLE (
    guide_article_id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    body TEXT NOT NULL,
    game_version VARCHAR(40),
    reference_url VARCHAR(300),
    reference_label VARCHAR(80),
    verified_on DATE,
    spoiler BOOLEAN NOT NULL DEFAULT FALSE,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE M_GUIDE_RULE (
    guide_rule_id BIGSERIAL PRIMARY KEY,
    guide_article_id BIGINT NOT NULL REFERENCES M_GUIDE_ARTICLE (guide_article_id) ON DELETE CASCADE,
    progress_definition_id BIGINT NOT NULL REFERENCES M_PROGRESS_DEFINITION (progress_definition_id),
    rule_type VARCHAR(10) NOT NULL,
    required_state VARCHAR(20) NOT NULL,
    CONSTRAINT ck_m_guide_rule_type CHECK (rule_type IN ('SHOW', 'HIDE')),
    CONSTRAINT ck_m_guide_rule_state CHECK (required_state IN ('ACHIEVED', 'NOT_ACHIEVED'))
);

INSERT INTO M_GAME_WORLD (game_id, world_key, generation, display_name, published, active, activated_at)
VALUES ('corekeeper', 'main', 1, 'Core Keeper ワールド', TRUE, TRUE, CURRENT_TIMESTAMP);

-- Boss IDs are the ones UpdateWorldInfoSystem turns into kill flags. Names are provisional
-- and editable from the maintenance screen.
INSERT INTO M_PROGRESS_DEFINITION (
  game_id, progress_key, category, display_name, description, support_status,
  source_kind, source_object_id, source_note, spoiler, sort_order
) VALUES
  ('corekeeper', 'great_wall_lowered', 'WORLD', '大壁の解放', 'ワールドの大壁が下がっているか。',
   'SUPPORTED', 'GREAT_WALL_STATUS', NULL, 'TheGreatWallStatusSerializedCD.HasBeenLowered', FALSE, 10),
  ('corekeeper', 'boss_bird', 'BOSS', 'Azeos the Sky Titan', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3090, 'KilledEnemiesSerializedBuffer / BirdBoss', TRUE, 100),
  ('corekeeper', 'boss_octopus', 'BOSS', 'Omoroth the Sea Titan', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3110, 'KilledEnemiesSerializedBuffer / OctopusBoss', TRUE, 110),
  ('corekeeper', 'boss_scarab', 'BOSS', 'Ra-Akar the Sand Titan', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3131, 'KilledEnemiesSerializedBuffer / ScarabBoss', TRUE, 120),
  ('corekeeper', 'boss_hydra_nature', 'BOSS', '自然のハイドラ（仮称）', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3145, 'KilledEnemiesSerializedBuffer / HydraBossNature', TRUE, 130),
  ('corekeeper', 'boss_hydra_sea', 'BOSS', '海のハイドラ（仮称）', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3146, 'KilledEnemiesSerializedBuffer / HydraBossSea', TRUE, 140),
  ('corekeeper', 'boss_hydra_desert', 'BOSS', '砂漠のハイドラ（仮称）', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3147, 'KilledEnemiesSerializedBuffer / HydraBossDesert', TRUE, 150),
  ('corekeeper', 'boss_core', 'BOSS', 'コアのボス（仮称）', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3157, 'KilledEnemiesSerializedBuffer / CoreBoss', TRUE, 160),
  ('corekeeper', 'boss_wall', 'BOSS', '大壁のボス（仮称）', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3168, 'KilledEnemiesSerializedBuffer / WallBoss', TRUE, 170),
  ('corekeeper', 'boss_giant_cicada', 'BOSS', '巨大セミのボス（仮称）', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3183, 'KilledEnemiesSerializedBuffer / GiantCicadaBoss', TRUE, 180),
  ('corekeeper', 'boss_robot', 'BOSS', '機械のボス（仮称）', NULL,
   'SUPPORTED', 'KILLED_OBJECT', 3190, 'KilledEnemiesSerializedBuffer / RobotBoss', TRUE, 190),
  -- The game does not derive kill flags for these from the record list; not yet verified.
  ('corekeeper', 'boss_slime', 'BOSS', 'Glurch the Abominous Mass', '討伐判定は未検証です。',
   'UNSUPPORTED', 'NONE', 3020, 'SlimeBoss', TRUE, 300),
  ('corekeeper', 'boss_larva', 'BOSS', 'Ghorm the Devourer', '討伐判定は未検証です。',
   'UNSUPPORTED', 'NONE', 3003, 'BossLarva', TRUE, 310),
  ('corekeeper', 'boss_hive', 'BOSS', 'The Hive Mother', '討伐判定は未検証です。',
   'UNSUPPORTED', 'NONE', 3005, 'LarvaHiveBoss', TRUE, 320),
  ('corekeeper', 'boss_shaman', 'BOSS', 'Malugaz the Corrupted', '討伐判定は未検証です。',
   'UNSUPPORTED', 'NONE', 3058, 'ShamanBoss', TRUE, 330);
