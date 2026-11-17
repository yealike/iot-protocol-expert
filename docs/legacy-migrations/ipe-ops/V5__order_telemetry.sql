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

CREATE TABLE ipe_ops.order_telemetry_outbox (
    id bigserial PRIMARY KEY,
    order_no varchar(40) NOT NULL,
    sample_time timestamptz NOT NULL,
    time_source varchar(16) NOT NULL,
    voltage numeric(20,6), current_amp numeric(20,6), power_kw numeric(20,6),
    gun_temp numeric(20,6), battery_temp numeric(20,6), soc numeric(8,3)
);
CREATE INDEX idx_telemetry_outbox_order ON ipe_ops.order_telemetry_outbox (order_no);
