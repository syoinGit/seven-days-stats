ALTER TABLE T_TIMELINE_POST
    ADD COLUMN visible BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE T_TIMELINE_POST
SET post_type = 'BLOOD_MOON'
WHERE source_type = 'WORLD_EVENT'
  AND message LIKE 'ブラッドムーン%';

-- Retain every observation, but show only the newest blood-moon forecast in each 24-hour window.
UPDATE T_TIMELINE_POST older
SET visible = FALSE
WHERE older.post_type = 'BLOOD_MOON'
  AND EXISTS (
    SELECT 1
    FROM T_TIMELINE_POST newer
    WHERE newer.post_type = 'BLOOD_MOON'
      AND newer.published_at > older.published_at
      AND newer.published_at <= older.published_at + INTERVAL '24' HOUR
  );

CREATE INDEX idx_t_timeline_post_visible_published
    ON T_TIMELINE_POST (visible, published_at DESC, timeline_post_id DESC);
