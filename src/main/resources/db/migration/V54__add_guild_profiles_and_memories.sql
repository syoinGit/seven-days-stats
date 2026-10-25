-- Profile shown on the resident page. Residents born before this stay NULL ("不明").
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN age INTEGER;
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN gender VARCHAR(20);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN occupation VARCHAR(40);
-- What the resident was born from, e.g. "7 Days to Die・10月3日 21時〜24時の記録". Public, no player data.
ALTER TABLE T_GUILD_SOURCE ADD COLUMN memory_label VARCHAR(200);
