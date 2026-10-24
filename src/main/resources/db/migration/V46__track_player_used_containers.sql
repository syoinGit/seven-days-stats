-- Octarine shows only player chests. The save does not say who placed or opened a container,
-- so the site decides from what it sees: a storage container that appears after tracking began,
-- or whose contents change, becomes a player chest for good. Merchants' stock is not stored.

-- Every storage container present in the latest snapshot, keyed by type and tile (two cannot
-- share a tile). The row is removed when the container disappears, so one placed again later
-- on the same tile counts as new.
CREATE TABLE T_CK_CONTAINER_TRACK (
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    object_id INTEGER NOT NULL,
    pos_x INTEGER NOT NULL,
    pos_z INTEGER NOT NULL,
    -- SHA-256 of the contents when first seen.
    first_content_hash VARCHAR(64) NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    player_chest BOOLEAN NOT NULL,
    player_chest_since TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (game_world_id, object_id, pos_x, pos_z)
);

-- Rows written under V45 covered every container, including untouched dungeon chests and
-- merchants. They are derived from the save; the next import starts tracking from scratch.
-- T_CK_CONTAINER keeps its holder column; only STORAGE rows are written from now on.
DELETE FROM T_CK_CONTAINER_ITEM;
DELETE FROM T_CK_CONTAINER;
DELETE FROM T_CK_ITEM_CHANGE;
DELETE FROM T_CK_ITEM_TOTAL;
-- Entity and crop counts are no longer collected (they include unexplored areas).
DELETE FROM T_CK_OBJECT_COUNT;
DELETE FROM T_CK_CROP_COUNT;
