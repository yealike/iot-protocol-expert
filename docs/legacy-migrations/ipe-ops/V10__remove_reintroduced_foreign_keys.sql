-- All application tables live in ipe_ops; no application foreign keys are retained.
DO $$
DECLARE item record;
BEGIN
    FOR item IN
        SELECT c.conname, n.nspname, t.relname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE c.contype = 'f' AND n.nspname = 'ipe_ops'
    LOOP
        EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT %I', item.nspname, item.relname, item.conname);
    END LOOP;
END $$;

