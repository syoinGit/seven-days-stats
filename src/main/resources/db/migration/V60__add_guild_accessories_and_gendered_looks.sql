-- Looks split by gender (GuildPeople): an accessory and an eye colour per resident, women's cuts of outfits,
-- and hair styles that belong to one gender. Existing residents keep their colours; only part names move.
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN accessory VARCHAR(20);
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN eye_color VARCHAR(7);

-- Tsukikage's people get their own dress.
UPDATE M_GUILD_CHARACTER SET outfit = 'samurai' WHERE occupation = '侍' AND outfit IS NOT NULL;
UPDATE M_GUILD_CHARACTER SET outfit = 'ronin' WHERE occupation = '浪人' AND outfit IS NOT NULL;
UPDATE M_GUILD_CHARACTER SET outfit = 'miko_m' WHERE occupation = '巫女' AND outfit IS NOT NULL;

-- Women wear the women's cut.
UPDATE M_GUILD_CHARACTER SET outfit = CASE outfit WHEN 'miko_m' THEN 'miko' ELSE outfit || '_f' END
  WHERE gender = '女性' AND outfit IN ('farmer', 'watch', 'knight', 'crafter', 'merchant', 'cleric', 'adventurer', 'hunter', 'cook',
    'samurai', 'ronin', 'miko_m');

-- Hair: long and tied were shared; men's become shaggy and a short tail, women's short and cropped become a bob.
UPDATE M_GUILD_CHARACTER SET hair_style = CASE hair_style WHEN 'long' THEN 'shaggy' ELSE 'tail' END
  WHERE gender = '男性' AND hair_style IN ('long', 'tied');
UPDATE M_GUILD_CHARACTER SET hair_style = 'bob' WHERE gender = '女性' AND hair_style IN ('short', 'cropped');
