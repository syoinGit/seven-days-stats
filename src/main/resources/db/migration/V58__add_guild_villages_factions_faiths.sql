-- Three small villages, four factions (and none), three faiths. Residents carry where they were born,
-- where they live now, their faction and faith; every change is kept in T_GUILD_AFFILIATION_HISTORY.
CREATE TABLE M_GUILD_VILLAGE (
  village_key VARCHAR(20) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  description VARCHAR(300) NOT NULL,
  sort_order INTEGER NOT NULL
);
CREATE TABLE M_GUILD_FACTION (
  faction_key VARCHAR(30) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  description VARCHAR(300) NOT NULL,
  -- Where the faction is seated; NULL for "none".
  village_key VARCHAR(20) REFERENCES M_GUILD_VILLAGE(village_key),
  sort_order INTEGER NOT NULL
);
CREATE TABLE M_GUILD_FAITH (
  faith_key VARCHAR(20) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  description VARCHAR(300) NOT NULL,
  sort_order INTEGER NOT NULL
);

INSERT INTO M_GUILD_VILLAGE (village_key, name, description, sort_order) VALUES
  ('grantford', 'グランフォード', '西洋風の村。領主、騎士、農民などが暮らす。', 10),
  ('elmwood', 'エルムウッド', '森林の村。狩人や薬草師が暮らし、自然信仰が盛ん。', 20),
  ('tsukikage', '月影郷', '和風の集落。侍、浪人、巫女などが暮らす。', 30);
INSERT INTO M_GUILD_FACTION (faction_key, name, description, village_key, sort_order) VALUES
  ('lord_house', 'グランフォード領主家', '領主とその家臣たち。', 'grantford', 10),
  ('silver_knights', '白銀の騎士団', '正義と名誉を重んじる騎士団。', 'grantford', 20),
  ('verdant_wardens', '翠葉の守人', '森林や自然を守る集団。', 'elmwood', 30),
  ('tsukikage_samurai', '月影武家衆', '月影郷を治める武家組織。', 'tsukikage', 40),
  ('none', '無所属', '特定の派閥に属さない。', NULL, 90);
INSERT INTO M_GUILD_FAITH (faith_key, name, description, sort_order) VALUES
  ('dawnlight', '暁光教', '光と秩序を重視する宗教。', 10),
  ('spirit', '精霊信仰', '自然や祖霊を敬う信仰。月影郷では八百万信仰として根付く。', 20),
  ('none', '無信仰', '特定の信仰を持たない。', 90);

ALTER TABLE M_GUILD_CHARACTER ADD COLUMN home_village VARCHAR(20);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN current_village VARCHAR(20);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN faction VARCHAR(30) NOT NULL DEFAULT 'none';
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN faith VARCHAR(20) NOT NULL DEFAULT 'none';

-- Existing residents: judged from what their memories already gave them (name lineage, occupation).
-- Anything those do not tell stays at the defaults: no faction, no faith.
UPDATE M_GUILD_CHARACTER SET home_village = CASE
    WHEN name_lineage = 'japanese' THEN 'tsukikage'
    WHEN occupation IN ('猟師', '薬師') THEN 'elmwood'
    ELSE 'grantford' END;
UPDATE M_GUILD_CHARACTER SET current_village = home_village;
UPDATE M_GUILD_CHARACTER SET faction = 'silver_knights' WHERE occupation = '騎士' AND home_village = 'grantford';
UPDATE M_GUILD_CHARACTER SET faith = CASE WHEN home_village = 'grantford' THEN 'dawnlight' ELSE 'spirit' END
  WHERE occupation = '聖職者';

ALTER TABLE M_GUILD_CHARACTER ALTER COLUMN home_village SET DEFAULT 'grantford';
ALTER TABLE M_GUILD_CHARACTER ALTER COLUMN current_village SET DEFAULT 'grantford';
ALTER TABLE M_GUILD_CHARACTER ALTER COLUMN home_village SET NOT NULL;
ALTER TABLE M_GUILD_CHARACTER ALTER COLUMN current_village SET NOT NULL;
ALTER TABLE M_GUILD_CHARACTER ADD CONSTRAINT fk_guild_home_village FOREIGN KEY (home_village) REFERENCES M_GUILD_VILLAGE(village_key);
ALTER TABLE M_GUILD_CHARACTER ADD CONSTRAINT fk_guild_current_village FOREIGN KEY (current_village) REFERENCES M_GUILD_VILLAGE(village_key);
ALTER TABLE M_GUILD_CHARACTER ADD CONSTRAINT fk_guild_faction FOREIGN KEY (faction) REFERENCES M_GUILD_FACTION(faction_key);
ALTER TABLE M_GUILD_CHARACTER ADD CONSTRAINT fk_guild_faith FOREIGN KEY (faith) REFERENCES M_GUILD_FAITH(faith_key);

-- kind: village (where they live), faction or faith. from_value is NULL for the first entry.
-- interaction_id points at the event that caused the change; NULL for births and the initial values.
CREATE TABLE T_GUILD_AFFILIATION_HISTORY (
  id BIGSERIAL PRIMARY KEY,
  character_id BIGINT NOT NULL REFERENCES M_GUILD_CHARACTER(id),
  kind VARCHAR(10) NOT NULL,
  from_value VARCHAR(30),
  to_value VARCHAR(30) NOT NULL,
  change_kind VARCHAR(20) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  interaction_id BIGINT REFERENCES T_GUILD_INTERACTION(id),
  changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_guild_affiliation_kind CHECK (kind IN ('village', 'faction', 'faith')),
  CONSTRAINT ck_guild_affiliation_change CHECK (change_kind IN ('initial', 'birth', 'move', 'join', 'leave', 'expel', 'convert'))
);
CREATE INDEX idx_guild_affiliation_character ON T_GUILD_AFFILIATION_HISTORY (character_id, changed_at);

INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id, kind, to_value, change_kind, reason)
  SELECT id, 'village', current_village, 'initial', '記録から判断した初期値' FROM M_GUILD_CHARACTER;
INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id, kind, to_value, change_kind, reason)
  SELECT id, 'faction', faction, 'initial', '記録から判断した初期値' FROM M_GUILD_CHARACTER;
INSERT INTO T_GUILD_AFFILIATION_HISTORY (character_id, kind, to_value, change_kind, reason)
  SELECT id, 'faith', faith, 'initial', '記録から判断した初期値' FROM M_GUILD_CHARACTER;
