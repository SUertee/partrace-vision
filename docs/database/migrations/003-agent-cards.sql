-- Explicit development upgrade for Agent cards; never invent cards for old results.
BEGIN;
LOCK TABLE runs IN ACCESS EXCLUSIVE MODE;

DO $body$
BEGIN
    IF EXISTS (SELECT 1 FROM runs WHERE status = 'completed') THEN
        RAISE EXCEPTION 'existing completed runs have no verified cards; automatic card backfill is forbidden'
            USING ERRCODE = '23514';
    END IF;
END;
$body$;

ALTER TABLE runs ADD COLUMN cards_json JSONB;
ALTER TABLE runs ADD CONSTRAINT ck_run_cards CHECK (
    cards_json IS NULL OR (
        jsonb_typeof(cards_json) = 'object'
        AND cards_json ->> 'version' = '1'
        AND jsonb_typeof(cards_json -> 'cards') = 'array'
        AND jsonb_typeof(cards_json -> 'query') = 'object'
    )
);
ALTER TABLE runs DROP CONSTRAINT ck_run_terminal;
ALTER TABLE runs ADD CONSTRAINT ck_run_terminal CHECK (
    (status = 'running' AND finished_at IS NULL AND error_json IS NULL AND cards_json IS NULL)
    OR (status = 'completed' AND finished_at IS NOT NULL AND error_json IS NULL AND cards_json IS NOT NULL)
    OR (status = 'stopped' AND finished_at IS NOT NULL AND error_json IS NULL AND cards_json IS NULL)
    OR (status = 'failed' AND finished_at IS NOT NULL AND error_json IS NOT NULL AND cards_json IS NULL)
);
COMMIT;
