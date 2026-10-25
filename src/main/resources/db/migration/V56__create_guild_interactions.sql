-- Hourly interactions between residents, the relationships they change and the loose threads they leave.
-- Code decides every outcome; the AI only writes the scene. Nothing here references source game records.

-- One row per JST hour that was considered: at most one interaction per hour, however often the job runs.
CREATE TABLE T_GUILD_HOURLY_JOB (
  slot_key VARCHAR(20) PRIMARY KEY,
  status VARCHAR(20) NOT NULL,
  message VARCHAR(300) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE T_GUILD_INTERACTION (
  id BIGSERIAL PRIMARY KEY,
  slot_key VARCHAR(20) NOT NULL UNIQUE,
  happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
  card_id VARCHAR(40) NOT NULL,
  cards_version VARCHAR(20) NOT NULL,
  kind VARCHAR(20) NOT NULL,
  selection VARCHAR(20) NOT NULL,
  outcome VARCHAR(40) NOT NULL,
  summary VARCHAR(200) NOT NULL,
  body VARCHAR(2000) NOT NULL,
  written_by VARCHAR(20) NOT NULL,
  CONSTRAINT ck_guild_interaction_writer CHECK (written_by IN ('ai', 'template'))
);
CREATE INDEX idx_guild_interaction_at ON T_GUILD_INTERACTION (happened_at);

CREATE TABLE T_GUILD_INTERACTION_PARTICIPANT (
  interaction_id BIGINT NOT NULL REFERENCES T_GUILD_INTERACTION(id),
  character_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  position INTEGER NOT NULL,
  PRIMARY KEY (interaction_id, character_id),
  UNIQUE (interaction_id, position)
);
CREATE INDEX idx_guild_participant_character ON T_GUILD_INTERACTION_PARTICIPANT (character_id);

-- Directed: how from_id feels about to_id. Axes are bounded; see GuildInteractionService.
CREATE TABLE T_GUILD_RELATION (
  from_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  to_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  trust INTEGER NOT NULL DEFAULT 0,
  affinity INTEGER NOT NULL DEFAULT 0,
  wariness INTEGER NOT NULL DEFAULT 0,
  debt INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (from_id, to_id),
  CONSTRAINT ck_guild_relation_self CHECK (from_id <> to_id),
  CONSTRAINT ck_guild_relation_range CHECK (trust BETWEEN -100 AND 100 AND affinity BETWEEN -100 AND 100
    AND wariness BETWEEN 0 AND 100 AND debt BETWEEN 0 AND 100)
);

-- The pair's current label (low_id < high_id). lead_id is the teacher of a 師弟 pair.
CREATE TABLE T_GUILD_BOND (
  low_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  high_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  label VARCHAR(20) NOT NULL,
  lead_id BIGINT REFERENCES M_GUILD_CHARACTER(id),
  since TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (low_id, high_id),
  CONSTRAINT ck_guild_bond_order CHECK (low_id < high_id),
  CONSTRAINT ck_guild_bond_label CHECK (label IN ('acquaintance', 'friend', 'close_friend', 'companion', 'lover', 'spouse', 'rival', 'mentor'))
);

-- Append-only: every axis change and label change, with the interaction that caused it.
CREATE TABLE T_GUILD_RELATION_EVENT (
  id BIGSERIAL PRIMARY KEY,
  interaction_id BIGINT NOT NULL REFERENCES T_GUILD_INTERACTION(id),
  from_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  to_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  change_kind VARCHAR(10) NOT NULL,
  trust_delta INTEGER NOT NULL DEFAULT 0,
  affinity_delta INTEGER NOT NULL DEFAULT 0,
  wariness_delta INTEGER NOT NULL DEFAULT 0,
  debt_delta INTEGER NOT NULL DEFAULT 0,
  label_before VARCHAR(20),
  label_after VARCHAR(20),
  reason VARCHAR(200) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_guild_relation_event_kind CHECK (change_kind IN ('axes', 'label')),
  UNIQUE (interaction_id, from_id, to_id, change_kind)
);
CREATE INDEX idx_guild_relation_event_pair ON T_GUILD_RELATION_EVENT (from_id, to_id);

-- Loose threads (約束・借り・遺恨・探し物・片思い) that later interactions can pick up and resolve.
CREATE TABLE T_GUILD_THREAD (
  id BIGSERIAL PRIMARY KEY,
  kind VARCHAR(20) NOT NULL,
  a_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  b_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  summary VARCHAR(200) NOT NULL,
  origin_interaction_id BIGINT NOT NULL REFERENCES T_GUILD_INTERACTION(id),
  resolved_interaction_id BIGINT REFERENCES T_GUILD_INTERACTION(id),
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  expires_on DATE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_guild_thread_kind CHECK (kind IN ('promise', 'debt', 'grudge', 'search', 'crush')),
  CONSTRAINT ck_guild_thread_status CHECK (status IN ('OPEN', 'RESOLVED', 'FADED'))
);
CREATE INDEX idx_guild_thread_pair ON T_GUILD_THREAD (a_id, b_id, status);
