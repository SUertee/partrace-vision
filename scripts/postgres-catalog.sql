-- Read-only schema introspection for PostgreSQL 15+; no business rows are read.
-- pg_get_* returns definitions from the loaded PostgreSQL catalog.
WITH target_tables AS (
    SELECT c.oid, c.relname
    FROM pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
), catalog_tables AS (
    SELECT t.relname, jsonb_build_object(
        'name', t.relname,
        'columns', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'name', a.attname, 'position', a.attnum,
                'type', pg_catalog.format_type(a.atttypid, a.atttypmod),
                'nullable', NOT a.attnotnull,
                'default', pg_catalog.pg_get_expr(d.adbin, d.adrelid),
                'identity', a.attidentity, 'generated', a.attgenerated
            ) ORDER BY a.attnum)
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
        ), '[]'::jsonb),
        'constraints', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'name', con.conname, 'type', con.contype,
                'definition', pg_catalog.pg_get_constraintdef(con.oid, true),
                'expression', CASE WHEN con.contype = 'c' THEN pg_catalog.pg_get_expr(con.conbin, con.conrelid) ELSE NULL END,
                'columns', COALESCE((
                    SELECT jsonb_agg(a.attname ORDER BY k.ordinality)
                    FROM unnest(con.conkey) WITH ORDINALITY k(attnum, ordinality)
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
                ), '[]'::jsonb),
                'to_table', ref.relname, 'to_schema', refns.nspname,
                'to_columns', COALESCE((
                    SELECT jsonb_agg(a.attname ORDER BY k.ordinality)
                    FROM unnest(con.confkey) WITH ORDINALITY k(attnum, ordinality)
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum
                ), '[]'::jsonb),
                'on_update_code', con.confupdtype, 'on_delete_code', con.confdeltype,
                'match_code', con.confmatchtype,
                'deferrable', con.condeferrable, 'initially_deferred', con.condeferred,
                'validated', con.convalidated
            ) ORDER BY con.conname)
            FROM pg_catalog.pg_constraint con
            LEFT JOIN pg_catalog.pg_class ref ON ref.oid = con.confrelid
            LEFT JOIN pg_catalog.pg_namespace refns ON refns.oid = ref.relnamespace
            WHERE con.conrelid = t.oid AND con.contype IN ('p', 'u', 'f', 'c')
        ), '[]'::jsonb),
        'indices', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'name', ic.relname, 'unique', ix.indisunique,
                'origin', CASE WHEN ix.indisprimary THEN 'pk' WHEN EXISTS (
                    SELECT 1 FROM pg_catalog.pg_constraint con WHERE con.conindid = ix.indexrelid AND con.contype = 'u'
                ) THEN 'u' ELSE 'c' END,
                'partial', ix.indpred IS NOT NULL,
                'predicate', pg_catalog.pg_get_expr(ix.indpred, ix.indrelid),
                'method', am.amname, 'valid', ix.indisvalid, 'ready', ix.indisready,
                'nulls_not_distinct', ix.indnullsnotdistinct,
                'sql', pg_catalog.pg_get_indexdef(ix.indexrelid),
                'columns', COALESCE((
                    SELECT jsonb_agg(COALESCE(a.attname, pg_catalog.pg_get_indexdef(ix.indexrelid, k.ordinality::integer, false)) ORDER BY k.ordinality)
                    FROM unnest(ix.indkey) WITH ORDINALITY k(attnum, ordinality)
                    LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum
                    WHERE k.ordinality <= ix.indnkeyatts
                ), '[]'::jsonb),
                'column_details', COALESCE((
                    SELECT jsonb_agg(jsonb_build_object(
                        'name', a.attname,
                        'expression', CASE WHEN k.attnum = 0 THEN pg_catalog.pg_get_indexdef(ix.indexrelid, k.ordinality::integer, false) ELSE NULL END,
                        'descending', CASE WHEN am.amname = 'btree' THEN (ix.indoption[(k.ordinality - 1)::integer] & 1) = 1 ELSE false END,
                        'collation', col.collname
                    ) ORDER BY k.ordinality)
                    FROM unnest(ix.indkey) WITH ORDINALITY k(attnum, ordinality)
                    LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum
                    LEFT JOIN pg_catalog.pg_collation col ON col.oid = ix.indcollation[(k.ordinality - 1)::integer]
                    WHERE k.ordinality <= ix.indnkeyatts
                ), '[]'::jsonb),
                'included_columns', COALESCE((
                    SELECT jsonb_agg(a.attname ORDER BY k.ordinality)
                    FROM unnest(ix.indkey) WITH ORDINALITY k(attnum, ordinality)
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum
                    WHERE k.ordinality > ix.indnkeyatts
                ), '[]'::jsonb)
            ) ORDER BY ic.relname)
            FROM pg_catalog.pg_index ix
            JOIN pg_catalog.pg_class ic ON ic.oid = ix.indexrelid
            JOIN pg_catalog.pg_am am ON am.oid = ic.relam
            WHERE ix.indrelid = t.oid
        ), '[]'::jsonb),
        'triggers', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'name', tg.tgname,
                'event', (CASE WHEN (tg.tgtype & 64) <> 0 THEN 'INSTEAD OF' WHEN (tg.tgtype & 2) <> 0 THEN 'BEFORE' ELSE 'AFTER' END) || ' ' || concat_ws(' OR ',
                    CASE WHEN (tg.tgtype & 4) <> 0 THEN 'INSERT' END,
                    CASE WHEN (tg.tgtype & 8) <> 0 THEN 'DELETE' END,
                    CASE WHEN (tg.tgtype & 16) <> 0 THEN 'UPDATE' END,
                    CASE WHEN (tg.tgtype & 32) <> 0 THEN 'TRUNCATE' END),
                'sql', pg_catalog.pg_get_triggerdef(tg.oid, true),
                'function_name', fn.oid::regprocedure::text,
                'function_definition', pg_catalog.pg_get_functiondef(fn.oid),
                'enabled', tg.tgenabled,
                'constraint', tg.tgconstraint <> 0,
                'deferrable', tg.tgdeferrable, 'initially_deferred', tg.tginitdeferred
            ) ORDER BY tg.tgname)
            FROM pg_catalog.pg_trigger tg
            JOIN pg_catalog.pg_proc fn ON fn.oid = tg.tgfoid
            WHERE tg.tgrelid = t.oid AND NOT tg.tgisinternal
        ), '[]'::jsonb)
    ) AS definition
    FROM target_tables t
)
SELECT jsonb_build_object(
    'schema', 'public',
    'postgres_version', current_setting('server_version'),
    'postgres_version_num', current_setting('server_version_num')::integer,
    'pgvector_version', (SELECT extversion FROM pg_catalog.pg_extension WHERE extname = 'vector'),
    'tables', COALESCE((SELECT jsonb_agg(definition ORDER BY relname) FROM catalog_tables), '[]'::jsonb)
);
