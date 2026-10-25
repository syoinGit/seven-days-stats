-- Decided by code at birth: name lineage and the pixel parts (src/main/resources/static/img/guild-people.svg).
-- Residents born before this stay NULL and keep the plain sprite.
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN name_lineage VARCHAR(20);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN outfit VARCHAR(20);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN hair_style VARCHAR(20);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN hair_color VARCHAR(7);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN skin_color VARCHAR(7);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN cloth_color VARCHAR(7);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN cloth2_color VARCHAR(7);
-- What the memory was mostly about (fishing, a blood moon...): spreads births across kinds of play.
ALTER TABLE T_GUILD_SOURCE ADD COLUMN category VARCHAR(20);
