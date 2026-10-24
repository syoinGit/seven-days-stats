-- Core Keeper (Octarine) contents. Snapshots, runs and progress reuse the V43 tables.
-- Latest contents are replaced when the save changes; change rows are append-only.
-- Object ids are the game's ObjectID numbers; names come from a table kept on the server.

CREATE TABLE T_CK_CONTAINER (
    ck_container_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    -- STORAGE: chests, pots, pedestals, dropped items, graves. NPC: a creature's stock (merchants).
    holder VARCHAR(10) NOT NULL,
    object_id INTEGER NOT NULL,
    variation INTEGER NOT NULL,
    -- World position in tiles; the Core is at (0, 0), +x east, +z north.
    pos_x INTEGER NOT NULL,
    pos_z INTEGER NOT NULL,
    slot_count INTEGER NOT NULL,
    filled_slots INTEGER NOT NULL,
    CONSTRAINT ck_t_ck_container_holder CHECK (holder IN ('STORAGE', 'NPC'))
);

CREATE INDEX idx_t_ck_container_world ON T_CK_CONTAINER (game_world_id);

CREATE TABLE T_CK_CONTAINER_ITEM (
    ck_container_item_id BIGSERIAL PRIMARY KEY,
    ck_container_id BIGINT NOT NULL REFERENCES T_CK_CONTAINER (ck_container_id) ON DELETE CASCADE,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    slot INTEGER NOT NULL,
    object_id INTEGER NOT NULL,
    -- Stack size for materials, remaining durability for equipment (the save does not say which).
    amount INTEGER NOT NULL,
    variation INTEGER NOT NULL
);

CREATE INDEX idx_t_ck_container_item_container ON T_CK_CONTAINER_ITEM (ck_container_id);
CREATE INDEX idx_t_ck_container_item_world_object ON T_CK_CONTAINER_ITEM (game_world_id, object_id);

-- One row per item ever stored (STORAGE holders only). Zero once it is no longer stored.
CREATE TABLE T_CK_ITEM_TOTAL (
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    object_id INTEGER NOT NULL,
    total_amount BIGINT NOT NULL,
    slot_count INTEGER NOT NULL,
    container_count INTEGER NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (game_world_id, object_id)
);

-- Recorded only between two successive snapshots; the first import is a baseline.
CREATE TABLE T_CK_ITEM_CHANGE (
    ck_item_change_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    object_id INTEGER NOT NULL,
    previous_amount BIGINT NOT NULL,
    new_amount BIGINT NOT NULL,
    previous_slots INTEGER NOT NULL,
    new_slots INTEGER NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_t_ck_item_change_world_observed ON T_CK_ITEM_CHANGE (game_world_id, observed_at);
CREATE INDEX idx_t_ck_item_change_object ON T_CK_ITEM_CHANGE (game_world_id, object_id, observed_at);

-- Entities per object id in the latest snapshot. Category comes from the entity's components.
CREATE TABLE T_CK_OBJECT_COUNT (
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    object_id INTEGER NOT NULL,
    category VARCHAR(20) NOT NULL,
    entity_count INTEGER NOT NULL,
    PRIMARY KEY (game_world_id, object_id, category)
);

CREATE TABLE T_CK_CROP_COUNT (
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    object_id INTEGER NOT NULL,
    stage INTEGER NOT NULL,
    entity_count INTEGER NOT NULL,
    PRIMARY KEY (game_world_id, object_id, stage)
);
