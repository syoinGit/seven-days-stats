-- A dungeon run changes how its party feel about each other once it is back (GuildDungeon.settleDue).
-- settled_at: when that was applied. Runs that came back before this rule existed count as settled, without changes.
ALTER TABLE T_GUILD_EXPEDITION ADD COLUMN settled_at TIMESTAMP WITH TIME ZONE;
UPDATE T_GUILD_EXPEDITION SET settled_at = returned_at WHERE returned_at <= CURRENT_TIMESTAMP;

-- A relation change comes from either an interaction or a dungeon run.
ALTER TABLE T_GUILD_RELATION_EVENT ALTER COLUMN interaction_id DROP NOT NULL;
ALTER TABLE T_GUILD_RELATION_EVENT ADD COLUMN expedition_id BIGINT REFERENCES T_GUILD_EXPEDITION(id);
ALTER TABLE T_GUILD_RELATION_EVENT ADD CONSTRAINT ck_guild_relation_event_source
  CHECK ((interaction_id IS NULL AND expedition_id IS NOT NULL) OR (interaction_id IS NOT NULL AND expedition_id IS NULL));
ALTER TABLE T_GUILD_RELATION_EVENT ADD CONSTRAINT uq_guild_relation_event_expedition UNIQUE (expedition_id, from_id, to_id, change_kind);
CREATE INDEX idx_guild_relation_event_expedition ON T_GUILD_RELATION_EVENT (expedition_id);
