-- Terraria (Luminite). Snapshots, runs and progress reuse the V43 world progress tables.
-- These tables hold the latest stored contents (replaced when the save changes) plus
-- append-only change rows. Raw saves are never stored.

CREATE TABLE T_TERRARIA_CONTAINER (
    terraria_container_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    container_kind VARCHAR(20) NOT NULL,
    tile_type INTEGER,
    tile_style INTEGER,
    tile_x INTEGER NOT NULL,
    tile_y INTEGER NOT NULL,
    -- Player-entered chest name; displayed escaped.
    name VARCHAR(100),
    slot_count INTEGER NOT NULL,
    filled_slots INTEGER NOT NULL,
    CONSTRAINT ck_t_terraria_container_kind CHECK (container_kind IN (
      'CHEST', 'DRESSER', 'ITEM_FRAME', 'WEAPON_RACK', 'MANNEQUIN', 'HAT_RACK', 'FOOD_PLATTER',
      'DISPLAY_JAR'))
);

CREATE INDEX idx_t_terraria_container_world ON T_TERRARIA_CONTAINER (game_world_id);

CREATE TABLE T_TERRARIA_CONTAINER_ITEM (
    terraria_container_item_id BIGSERIAL PRIMARY KEY,
    terraria_container_id BIGINT NOT NULL
      REFERENCES T_TERRARIA_CONTAINER (terraria_container_id) ON DELETE CASCADE,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    slot INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    stack INTEGER NOT NULL,
    prefix_id INTEGER NOT NULL
);

CREATE INDEX idx_t_terraria_container_item_container ON T_TERRARIA_CONTAINER_ITEM (terraria_container_id);
CREATE INDEX idx_t_terraria_container_item_world_item ON T_TERRARIA_CONTAINER_ITEM (game_world_id, item_id);

-- One row per item ever seen in this world generation. total = 0 once it is no longer stored.
CREATE TABLE T_TERRARIA_ITEM_TOTAL (
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    item_id INTEGER NOT NULL,
    total BIGINT NOT NULL,
    container_count INTEGER NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (game_world_id, item_id)
);

-- Recorded only between two successive snapshots; the first import is a baseline, not a change.
CREATE TABLE T_TERRARIA_ITEM_CHANGE (
    terraria_item_change_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    item_id INTEGER NOT NULL,
    previous_total BIGINT NOT NULL,
    new_total BIGINT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_t_terraria_item_change_world_observed ON T_TERRARIA_ITEM_CHANGE (game_world_id, observed_at);
CREATE INDEX idx_t_terraria_item_change_item ON T_TERRARIA_ITEM_CHANGE (game_world_id, item_id, observed_at);

-- Bestiary state keyed by the game's persistent NPC id (e.g. "BlueSlime").
CREATE TABLE T_TERRARIA_BESTIARY (
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    npc_key VARCHAR(80) NOT NULL,
    kills INTEGER NOT NULL,
    sighted BOOLEAN NOT NULL,
    chatted BOOLEAN NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (game_world_id, npc_key)
);

CREATE TABLE T_TERRARIA_KILL_CHANGE (
    terraria_kill_change_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    npc_key VARCHAR(80) NOT NULL,
    previous_kills INTEGER NOT NULL,
    new_kills INTEGER NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_t_terraria_kill_change_world_observed ON T_TERRARIA_KILL_CHANGE (game_world_id, observed_at);

-- Signs, tombstones and announcement boxes. Tombstone text is the game's death message.
CREATE TABLE T_TERRARIA_SIGN (
    terraria_sign_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    tile_type INTEGER,
    tile_x INTEGER NOT NULL,
    tile_y INTEGER NOT NULL,
    text VARCHAR(4000) NOT NULL,
    -- NULL when the sign already existed at the first import of this world generation.
    first_seen_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_t_terraria_sign_world ON T_TERRARIA_SIGN (game_world_id);

INSERT INTO M_GAME_WORLD (game_id, world_key, generation, display_name, published, active, activated_at)
VALUES ('terraria', 'main', 1, 'Terraria ワールド', TRUE, TRUE, CURRENT_TIMESTAMP);

-- Names follow the game's Japanese text and stay editable from the maintenance screen.
-- source_note is the saved flag; "Saved"/"Unlocked" mean the NPC was freed or qualified once,
-- not that it lives in the world now.
INSERT INTO M_PROGRESS_DEFINITION (
  game_id, progress_key, category, display_name, description, support_status,
  source_kind, source_object_id, source_note, spoiler, sort_order
) VALUES
  ('terraria', 'boss_king_slime', 'BOSS', 'スライムキング', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedSlimeKingBoss', FALSE, 100),
  ('terraria', 'boss_eye', 'BOSS', 'クトゥルフのめだま', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedBoss1EyeofCthulhu', FALSE, 110),
  ('terraria', 'boss_evil', 'BOSS', 'イーターオブワールド／クトゥルフののうみそ',
   'ゲームはどちらを倒しても同じフラグを立てるため、どちらを倒したかは区別できません。',
   'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedBoss2EaterofWorlds', FALSE, 120),
  ('terraria', 'boss_queen_bee', 'BOSS', 'クイーンビー', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedQueenBee', FALSE, 130),
  ('terraria', 'boss_skeletron', 'BOSS', 'スケルトロン', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedBoss3Skeletron', FALSE, 140),
  ('terraria', 'boss_deerclops', 'BOSS', 'ディアクロップス', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedDeerclops', FALSE, 150),
  ('terraria', 'hard_mode', 'BOSS', 'ウォールオブフレッシュ（ハードモード）',
   'ハードモードに移行しているか。ウォールオブフレッシュ専用の討伐フラグは保存されていません。',
   'SUPPORTED', 'WORLD_FLAG', NULL, 'HardMode', FALSE, 160),
  ('terraria', 'boss_queen_slime', 'BOSS', 'クイーンスライム', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedQueenSlime', FALSE, 200),
  ('terraria', 'boss_destroyer', 'BOSS', 'デストロイヤー', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedMechBoss1TheDestroyer', FALSE, 210),
  ('terraria', 'boss_twins', 'BOSS', 'ツインズ', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedMechBoss2TheTwins', FALSE, 220),
  ('terraria', 'boss_skeletron_prime', 'BOSS', 'スケルトロン・プライム', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedMechBoss3SkeletronPrime', FALSE, 230),
  ('terraria', 'boss_plantera', 'BOSS', 'プランテラ', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedPlantBoss', FALSE, 240),
  ('terraria', 'boss_golem', 'BOSS', 'ゴーレム', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedGolemBoss', FALSE, 250),
  ('terraria', 'boss_duke_fishron', 'BOSS', 'フィッシュロンこうしゃく', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedFishron', FALSE, 260),
  ('terraria', 'boss_empress', 'BOSS', 'ひかりのじょてい', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedEmpressOfLight', FALSE, 270),
  ('terraria', 'boss_cultist', 'BOSS', 'くるったカルト', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedLunaticCultist', FALSE, 280),
  ('terraria', 'pillar_solar', 'BOSS', 'しゃくねつのはしら', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedCelestialSolar', FALSE, 290),
  ('terraria', 'pillar_vortex', 'BOSS', 'ボルテックスのはしら', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedCelestialVortex', FALSE, 291),
  ('terraria', 'pillar_nebula', 'BOSS', 'ネビュラのはしら', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedCelestialNebula', FALSE, 292),
  ('terraria', 'pillar_stardust', 'BOSS', 'スターダストのはしら', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedCelestialStardust', FALSE, 293),
  ('terraria', 'boss_moon_lord', 'BOSS', 'ムーンロード', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedMoonlord', FALSE, 300),
  ('terraria', 'event_goblins', 'EVENT', 'ゴブリンしんりゃく', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedGoblins', FALSE, 400),
  ('terraria', 'event_frost_legion', 'EVENT', 'フロストぐんだん', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedFrost', FALSE, 410),
  ('terraria', 'event_pirates', 'EVENT', 'かいぞくのしゅうらい', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedPirates', FALSE, 420),
  ('terraria', 'event_martians', 'EVENT', 'かせいじんの狂気', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedMartians', FALSE, 430),
  ('terraria', 'event_old_ones_army_1', 'EVENT', 'オールドワンぐんだん（ティア1）', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedDD2InvasionT1', FALSE, 440),
  ('terraria', 'event_old_ones_army_2', 'EVENT', 'オールドワンぐんだん（ティア2）', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedDD2InvasionT2', FALSE, 441),
  ('terraria', 'event_old_ones_army_3', 'EVENT', 'オールドワンぐんだん（ティア3）', NULL, 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedDD2InvasionT3', FALSE, 442),
  ('terraria', 'event_mourning_wood', 'EVENT', 'イビルウッド', 'パンプキンムーンのボス。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedHalloweenTree', FALSE, 450),
  ('terraria', 'event_pumpking', 'EVENT', 'パンプキング', 'パンプキンムーンのボス。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedHalloweenKing', FALSE, 451),
  ('terraria', 'event_everscream', 'EVENT', 'エバースクリーム', 'フロストムーンのボス。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedChristmasTree', FALSE, 460),
  ('terraria', 'event_santa_nk1', 'EVENT', 'サンタ-NK1', 'フロストムーンのボス。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedSanta', FALSE, 461),
  ('terraria', 'event_ice_queen', 'EVENT', 'こおりのじょおう', 'フロストムーンのボス。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedChristmasQueen', FALSE, 462),
  ('terraria', 'event_clown', 'EVENT', 'ピエロ', 'ブラッドムーンに出現する敵。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'DownedClown', FALSE, 470),
  ('terraria', 'npc_goblin', 'NPC', 'ゴブリンのよろずや', '救出したことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedGoblin', FALSE, 500),
  ('terraria', 'npc_wizard', 'NPC', 'まほうつかい', '救出したことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedWizard', FALSE, 501),
  ('terraria', 'npc_mechanic', 'NPC', 'メカニック', '救出したことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedMech', FALSE, 502),
  ('terraria', 'npc_angler', 'NPC', 'つりびと', '起こしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedAngler', FALSE, 503),
  ('terraria', 'npc_stylist', 'NPC', 'スタイリスト', '救出したことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedStylist', FALSE, 504),
  ('terraria', 'npc_tax_collector', 'NPC', 'ぜいきんコレクター', '浄化したことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedTaxCollector', FALSE, 505),
  ('terraria', 'npc_golfer', 'NPC', 'ゴルファー', '救出したことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedGolfer', FALSE, 506),
  ('terraria', 'npc_bartender', 'NPC', 'いざかや', '起こしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'SavedBartender', FALSE, 507),
  ('terraria', 'npc_merchant', 'NPC', 'しょうにん', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedMerchantSpawn', FALSE, 520),
  ('terraria', 'npc_nurse', 'NPC', 'ナース', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedNurseSpawn', FALSE, 521),
  ('terraria', 'npc_demolitionist', 'NPC', 'ばくはギシ', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedDemolitionistSpawn', FALSE, 522),
  ('terraria', 'npc_dye_trader', 'NPC', 'せんしょくし', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedDyeTraderSpawn', FALSE, 523),
  ('terraria', 'npc_arms_dealer', 'NPC', 'ぶきしょうにん', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedArmsDealerSpawn', FALSE, 524),
  ('terraria', 'npc_party_girl', 'NPC', 'パーティーガール', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedPartyGirlSpawn', FALSE, 525),
  ('terraria', 'npc_truffle', 'NPC', 'トリュフ', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedTruffleSpawn', FALSE, 526),
  ('terraria', 'npc_princess', 'NPC', '王女', '入居条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedPrincessSpawn', FALSE, 527),
  ('terraria', 'npc_cat', 'NPC', 'ネコ', 'ネコのめんきょを使ったことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'BoughtCat', FALSE, 540),
  ('terraria', 'npc_dog', 'NPC', 'イヌ', 'イヌのめんきょを使ったことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'BoughtDog', FALSE, 541),
  ('terraria', 'npc_bunny', 'NPC', 'ウサギ', 'ウサギのめんきょを使ったことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'BoughtBunny', FALSE, 542),
  ('terraria', 'npc_slime_blue', 'NPC', 'オタクのスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimeBlueSpawn', FALSE, 560),
  ('terraria', 'npc_slime_green', 'NPC', 'かっこいいスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimeGreenSpawn', FALSE, 561),
  ('terraria', 'npc_slime_old', 'NPC', 'としおいたスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimeOldSpawn', FALSE, 562),
  ('terraria', 'npc_slime_purple', 'NPC', 'ぶきようなスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimePurpleSpawn', FALSE, 563),
  ('terraria', 'npc_slime_rainbow', 'NPC', 'うたひめのスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimeRainbowSpawn', FALSE, 564),
  ('terraria', 'npc_slime_red', 'NPC', 'ぶあいそうなスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimeRedSpawn', FALSE, 565),
  ('terraria', 'npc_slime_yellow', 'NPC', 'しんぴてきなスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimeYellowSpawn', FALSE, 566),
  ('terraria', 'npc_slime_copper', 'NPC', 'りっぱなスライム', '仲間になる条件を満たしたことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'UnlockedSlimeCopperSpawn', FALSE, 567),
  ('terraria', 'world_orb', 'WORLD', 'シャドウオーブ／クリムゾンハートの破壊', '一度でも壊したことがあるか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'ShadowOrbSmashed', FALSE, 600),
  ('terraria', 'world_combat_book', 'WORLD', 'こうどなせんとうテクニック', '使用済みか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'CombatBookUsed', FALSE, 610),
  ('terraria', 'world_combat_book_2', 'WORLD', 'こうどなせんとうテクニック：２かん', '使用済みか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'CombatBookVolumeTwoWasUsed', FALSE, 611),
  ('terraria', 'world_peddlers_satchel', 'WORLD', '行商人のカバン', '使用済みか。', 'SUPPORTED', 'WORLD_FLAG', NULL, 'PeddlersSatchelWasUsed', FALSE, 612);
