-- What the party is doing once a stage is out ("戦闘中"), shown as "古い坑道で戦闘中". NULL for stages saved before this.
ALTER TABLE T_GUILD_EXPEDITION_STAGE ADD COLUMN doing VARCHAR(40);
