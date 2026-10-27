-- Rumors (GuildRumors): what someone heard of an interaction they were not in, kept as one of their thoughts. A rumor
-- becomes what is on their mind (about_id null) like any other thought; kind tells it apart, so the tavern can let
-- them murmur it. Existing thoughts are their own ('mind').
ALTER TABLE T_GUILD_THOUGHT ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'mind';
ALTER TABLE T_GUILD_THOUGHT ADD CONSTRAINT ck_guild_thought_kind CHECK (kind IN ('mind', 'rumor'));
CREATE INDEX idx_guild_thought_about ON T_GUILD_THOUGHT (about_id);
