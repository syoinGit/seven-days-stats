-- Core Keeper (Octarine) contents. Snapshots, runs and progress reuse the V43 tables.
-- Latest contents are replaced when the save changes; change rows are append-only.
-- Object ids are the game's ObjectID numbers; names come from a table kept on the server.
--
-- The save does not say who placed or opened a container, so only containers whose contents
-- have changed since the site first saw them count as known to the players. Untouched dungeon
-- chests and merchants' stock are never stored in the content tables.

-- Every storage container seen, keyed by type and tile (two cannot share a tile).
CREATE TABLE T_CK_CONTAINER_TRACK (
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    object_id INTEGER NOT NULL,
    pos_x INTEGER NOT NULL,
    pos_z INTEGER NOT NULL,
    -- SHA-256 of the contents when first seen.
    first_content_hash VARCHAR(64) NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Set once the contents differ from the first sighting; never cleared.
    known_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (game_world_id, object_id, pos_x, pos_z)
);

-- Known containers in the latest snapshot.
CREATE TABLE T_CK_CONTAINER (
    ck_container_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    object_id INTEGER NOT NULL,
    variation INTEGER NOT NULL,
    -- World position in tiles; the Core is at (0, 0), +x east, +z north.
    pos_x INTEGER NOT NULL,
    pos_z INTEGER NOT NULL,
    slot_count INTEGER NOT NULL,
    filled_slots INTEGER NOT NULL
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

-- One row per item ever stored in a known container. Zero once it is no longer stored.
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
