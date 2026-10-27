-- Stable visual choices; changing a face never changes the resident's story.
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN eye_style VARCHAR(20) NOT NULL DEFAULT 'round';
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN brow_style VARCHAR(20) NOT NULL DEFAULT 'natural';
ALTER TABLE M_GUILD_CHARACTER ADD COLUMN outfit_variant VARCHAR(20) NOT NULL DEFAULT 'classic';

-- Give established layered residents different eyes once, retaining all existing hair, colours and clothes.
UPDATE M_GUILD_CHARACTER SET eye_style = CASE MOD(id, 9)
  WHEN 0 THEN 'round' WHEN 1 THEN 'almond' WHEN 2 THEN 'droop' WHEN 3 THEN 'sharp'
  WHEN 4 THEN 'sleepy' WHEN 5 THEN 'wide' WHEN 6 THEN 'narrow' WHEN 7 THEN 'sparkle' ELSE 'smile' END,
  brow_style = CASE MOD(id, 3) WHEN 0 THEN 'natural' WHEN 1 THEN 'straight' ELSE 'arched' END
  WHERE outfit IS NOT NULL;
