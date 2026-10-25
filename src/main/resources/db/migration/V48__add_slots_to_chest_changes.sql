-- Equipment stores durability where stacks store a count, so a chest change also keeps the number
-- of slots: "1個 増えた" for equipment instead of "+200". NULL on rows written before this.
ALTER TABLE T_CK_CHEST_CHANGE ADD COLUMN previous_slots INTEGER;
ALTER TABLE T_CK_CHEST_CHANGE ADD COLUMN new_slots INTEGER;
