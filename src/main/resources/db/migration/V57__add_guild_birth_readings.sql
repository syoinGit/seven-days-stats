-- Residents are now decided by the AI after reading the memory and the world around it.
-- reading: what it took from the records (public, shown as the reason for the person).
-- world_facts: the day, achievement rates and one chest it read (administrators only; chest
-- contents are not public elsewhere on the site). Residents born before this stay NULL.
ALTER TABLE T_GUILD_SOURCE ADD COLUMN reading VARCHAR(300);
ALTER TABLE T_GUILD_SOURCE ADD COLUMN world_facts VARCHAR(1600);
