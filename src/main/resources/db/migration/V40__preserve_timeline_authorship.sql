-- Game-player links are editable. Post ownership must remain with the original portal account.
ALTER TABLE T_TIMELINE_POST ADD COLUMN author_account_id BIGINT;
ALTER TABLE T_TIMELINE_POST ADD CONSTRAINT fk_t_timeline_post_author
  FOREIGN KEY (author_account_id) REFERENCES M_WEB_ACCOUNT(id) ON DELETE SET NULL;

-- V23 copied legacy posts with their original post ID. Recover only recorded authorship;
-- do not infer ownership from today's player/account links or reinsert deleted posts.
UPDATE T_TIMELINE_POST
SET author_account_id = (
  SELECT legacy.account_id FROM T_PLAYER_POST legacy
  WHERE legacy.id = T_TIMELINE_POST.source_id
)
WHERE post_type = 'PLAYER_MESSAGE' AND source_type = 'PLAYER_POST' AND source_id IS NOT NULL;

-- Posts written after V23 encode the immutable account ID before the timestamp.
UPDATE T_TIMELINE_POST
SET author_account_id = (
  SELECT account.id FROM M_WEB_ACCOUNT account
  WHERE T_TIMELINE_POST.source_hash LIKE 'PLAYER!_POST:' || CAST(account.id AS VARCHAR) || ':%' ESCAPE '!'
)
WHERE post_type = 'PLAYER_MESSAGE' AND source_type = 'PLAYER_POST' AND source_id IS NULL;

CREATE INDEX idx_t_timeline_post_author ON T_TIMELINE_POST(author_account_id);
