-- Octarine shows only player chests: storage containers the players gave a label in game.
-- The save does not say who placed or opened a container, but a label is something only
-- players write. World chests, merchants' stock, graves and dropped items are not stored.

-- The text written on the chest in game (UTF-8, at most 1024 bytes in the save).
ALTER TABLE T_CK_CONTAINER ADD COLUMN label VARCHAR(1024);

-- Item changes inside one player chest between two successive snapshots. Only chests that
-- were player chests in both are compared; labelling or removing a chest logs nothing.
CREATE TABLE T_CK_CHEST_CHANGE (
    ck_chest_change_id BIGSERIAL PRIMARY KEY,
    game_world_id BIGINT NOT NULL REFERENCES M_GAME_WORLD (game_world_id),
    world_snapshot_id BIGINT NOT NULL REFERENCES T_WORLD_SNAPSHOT (world_snapshot_id),
    chest_object_id INTEGER NOT NULL,
    pos_x INTEGER NOT NULL,
    pos_z INTEGER NOT NULL,
    -- The label when the change was seen; labels can be rewritten later.
    label VARCHAR(1024) NOT NULL,
    object_id INTEGER NOT NULL,
    previous_amount BIGINT NOT NULL,
    new_amount BIGINT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_t_ck_chest_change_world_observed ON T_CK_CHEST_CHANGE (game_world_id, observed_at);

-- Rows written under V45 covered every container, including untouched dungeon chests and
-- merchants. They are derived from the save and rebuilt by the next import.
DELETE FROM T_CK_CONTAINER_ITEM;
DELETE FROM T_CK_CONTAINER;
DELETE FROM T_CK_ITEM_CHANGE;
DELETE FROM T_CK_ITEM_TOTAL;
-- Entity and crop counts are no longer collected (they include unexplored areas).
DELETE FROM T_CK_OBJECT_COUNT;
DELETE FROM T_CK_CROP_COUNT;
