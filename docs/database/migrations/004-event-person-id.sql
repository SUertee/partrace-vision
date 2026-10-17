-- Add only nullable A-gallery identity; never infer or rewrite old event facts.
BEGIN;
ALTER TABLE events ADD COLUMN person_id TEXT;
ALTER TABLE events ADD CONSTRAINT ck_event_person
    CHECK (person_id IS NULL OR person_id ~ '^G[0-9]{4,99}$');
COMMIT;
