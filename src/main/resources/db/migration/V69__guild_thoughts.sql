-- Where a resident's mind is now: after each interaction the AI writes, in the resident's own words, what they are thinking
-- about (about_id null) and how they see the one they talked with (about_id set). Append-only; the newest row is the
-- current one, and the rows before it show how it moved. Later scenes and talks are given the newest ones.
CREATE TABLE T_GUILD_THOUGHT (
  id BIGSERIAL PRIMARY KEY,
  character_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  about_id BIGINT REFERENCES M_GUILD_CHARACTER(id),
  interaction_id BIGINT NOT NULL REFERENCES T_GUILD_INTERACTION(id),
  text VARCHAR(200) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_guild_thought_self CHECK (about_id IS NULL OR about_id <> character_id)
);
CREATE INDEX idx_guild_thought_character ON T_GUILD_THOUGHT (character_id, about_id, id);
CREATE INDEX idx_guild_thought_interaction ON T_GUILD_THOUGHT (interaction_id);
