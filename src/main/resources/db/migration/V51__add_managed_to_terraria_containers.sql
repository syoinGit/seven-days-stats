-- Managed storage: containers near a town NPC's home or a pylon (TerrariaStorage).
-- Existing rows stay visible until the next import replaces them with the rule applied.
ALTER TABLE T_TERRARIA_CONTAINER ADD COLUMN managed BOOLEAN NOT NULL DEFAULT TRUE;
