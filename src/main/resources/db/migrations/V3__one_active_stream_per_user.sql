-- Close all but the newest active stream per user so the index can be created
UPDATE streams s
SET end_time = COALESCE(s.last_seen, NOW())
WHERE s.end_time IS NULL
  AND EXISTS (SELECT 1 FROM streams n WHERE n.user_id = s.user_id AND n.end_time IS NULL AND n.id > s.id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_streams_one_active_per_user
    ON streams (user_id)
    WHERE end_time IS NULL;
