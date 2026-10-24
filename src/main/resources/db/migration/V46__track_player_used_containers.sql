-- Octarine shows only containers the players have used. The save does not say who placed or
-- opened a container, so a container counts as known once its contents differ from the first
-- time the site saw it. Merchants' stock is no longer stored.

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
