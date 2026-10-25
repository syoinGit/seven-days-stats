-- Provisional dungeon runs: once a day, 1-4 of the day's visitors leave the tavern for a dungeon.
-- Code simulates the whole run when they leave; its four stages (起承転結) become readable one by one
-- at reveal_at, and the result only with the last stage.
CREATE TABLE T_GUILD_EXPEDITION (
  id BIGSERIAL PRIMARY KEY,
  expedition_date DATE NOT NULL UNIQUE,
  dungeon_key VARCHAR(40) NOT NULL,
  dungeon_name VARCHAR(40) NOT NULL,
  goal VARCHAR(200) NOT NULL,
  result VARCHAR(10) NOT NULL,
  cards_version VARCHAR(40) NOT NULL,
  departed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  returned_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_guild_expedition_result CHECK (result IN ('success', 'partial', 'retreat')),
  CONSTRAINT ck_guild_expedition_time CHECK (departed_at < returned_at)
);
CREATE TABLE T_GUILD_EXPEDITION_MEMBER (
  expedition_id BIGINT NOT NULL REFERENCES T_GUILD_EXPEDITION(id),
  character_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  position INTEGER NOT NULL,
  PRIMARY KEY (expedition_id, character_id),
  CONSTRAINT ck_guild_expedition_member_position CHECK (position BETWEEN 0 AND 3)
);
CREATE INDEX idx_guild_expedition_member_character ON T_GUILD_EXPEDITION_MEMBER (character_id);
-- written_by is 'template' for now; an AI writer may take over the stage text later.
CREATE TABLE T_GUILD_EXPEDITION_STAGE (
  id BIGSERIAL PRIMARY KEY,
  expedition_id BIGINT NOT NULL REFERENCES T_GUILD_EXPEDITION(id),
  stage INTEGER NOT NULL,
  card_id VARCHAR(60) NOT NULL,
  hero_id BIGINT REFERENCES M_GUILD_CHARACTER(id),
  body VARCHAR(500) NOT NULL,
  reveal_at TIMESTAMP WITH TIME ZONE NOT NULL,
  written_by VARCHAR(10) NOT NULL DEFAULT 'template',
  UNIQUE (expedition_id, stage),
  CONSTRAINT ck_guild_expedition_stage CHECK (stage BETWEEN 1 AND 4),
  CONSTRAINT ck_guild_expedition_written_by CHECK (written_by IN ('template', 'ai'))
);
