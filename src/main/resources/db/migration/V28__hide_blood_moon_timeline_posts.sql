-- Blood-moon observations remain in the raw world-event log and power the dashboard countdown.
-- The dedicated timeline forecast duplicates that sidebar, so retain it but remove it from the feed.
UPDATE T_TIMELINE_POST
SET visible = FALSE
WHERE post_type = 'BLOOD_MOON';
