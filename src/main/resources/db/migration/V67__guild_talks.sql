-- Talks with a resident: the AI writes one short talk fitting a resident (their first line and three answers a
-- visitor can give, each with the resident's reply). One is written each evening (origin 'schedule', for a resident
-- who is in some place that day) and administrators may write one more a day (origin 'manual'). Which answer a
-- visitor chose is kept only in their browser, never here.
CREATE TABLE T_GUILD_TALK (
  id BIGSERIAL PRIMARY KEY,
  talk_date DATE NOT NULL,
  origin VARCHAR(10) NOT NULL,
  character_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  place VARCHAR(20) NOT NULL,
  opening VARCHAR(300) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (talk_date, origin),
  CONSTRAINT ck_guild_talk_origin CHECK (origin IN ('schedule', 'manual')),
  CONSTRAINT ck_guild_talk_place CHECK (place IN ('guild', 'grantford', 'elmwood', 'tsukikage'))
);
CREATE INDEX idx_guild_talk_character ON T_GUILD_TALK (character_id, id);
CREATE TABLE T_GUILD_TALK_CHOICE (
  talk_id BIGINT NOT NULL REFERENCES T_GUILD_TALK(id),
  position INTEGER NOT NULL,
  label VARCHAR(100) NOT NULL,
  reply VARCHAR(400) NOT NULL,
  PRIMARY KEY (talk_id, position),
  CONSTRAINT ck_guild_talk_choice_position CHECK (position BETWEEN 0 AND 2)
);
