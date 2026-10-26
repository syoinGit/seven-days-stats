-- The world grows to three nations (the three villages) around the unattended guild in the middle. Each day ten
-- residents come to the guild and five to each nation's own tavern. place: 'guild' (the unattended guild) or a
-- village key. Seats stay unique per day across every place: the guild 0-9, Grantford 10-14, Elmwood 15-19,
-- Tsukikage 20-24. Existing visits and interactions all happened at the guild.
ALTER TABLE T_GUILD_VISIT DROP CONSTRAINT ck_guild_visit_seat;
ALTER TABLE T_GUILD_VISIT ADD CONSTRAINT ck_guild_visit_seat CHECK (seat BETWEEN 0 AND 24);
ALTER TABLE T_GUILD_VISIT ADD COLUMN place VARCHAR(20) NOT NULL DEFAULT 'guild';
ALTER TABLE T_GUILD_VISIT ADD CONSTRAINT ck_guild_visit_place CHECK (place IN ('guild', 'grantford', 'elmwood', 'tsukikage'));

ALTER TABLE T_GUILD_INTERACTION ADD COLUMN place VARCHAR(20) NOT NULL DEFAULT 'guild';
ALTER TABLE T_GUILD_INTERACTION ADD CONSTRAINT ck_guild_interaction_place CHECK (place IN ('guild', 'grantford', 'elmwood', 'tsukikage'));
