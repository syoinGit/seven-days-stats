-- The 7DTD diary is gone (pages and generation). Its timeline posts linked to /diaries/{date}, which no longer
-- exists, so hide them. Nothing is deleted: the posts and T_AI_COMMENT rows stay, only out of the feed.
UPDATE T_TIMELINE_POST
SET visible = FALSE
WHERE post_type = 'DIARY';
