ALTER TABLE T_TIMELINE_POST ADD COLUMN image_url VARCHAR(1200);
ALTER TABLE T_TIMELINE_POST ADD COLUMN base_like_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE T_TIMELINE_POST ADD COLUMN post_subtype VARCHAR(40);

ALTER TABLE T_TIMELINE_POST
    ADD CONSTRAINT ck_timeline_post_base_like_non_negative CHECK (base_like_count >= 0);

CREATE INDEX idx_t_timeline_post_type_published
    ON T_TIMELINE_POST (post_type, published_at DESC);
