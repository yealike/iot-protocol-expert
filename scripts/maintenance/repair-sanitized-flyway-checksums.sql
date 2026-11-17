-- Run manually before starting the sanitized release on an existing OPS database.
-- Only known historical V1/V3 checksums are accepted. No application data is changed.
-- psql -v ON_ERROR_STOP=1 -f scripts/maintenance/repair-sanitized-flyway-checksums.sql
begin;
lock table ipe_ops.flyway_schema_history in exclusive mode;
do $repair$
begin
    if exists (select 1 from ipe_ops.flyway_schema_history
               where version = '1' and (checksum is distinct from 604830834
                   and checksum is distinct from 1371122243
                   or script is distinct from 'V1__init.sql' or success is distinct from true)) then
        raise exception 'Unexpected Flyway V1 state; stop and investigate before repairing';
    end if;
    update ipe_ops.flyway_schema_history set checksum = 1371122243
    where version = '1' and checksum = 604830834
        and script = 'V1__init.sql' and success = true;
    if exists (select 1 from ipe_ops.flyway_schema_history
               where version = '3' and (checksum is distinct from -834061969
                   and checksum is distinct from 1843662674
                   or script is distinct from 'V3__update_demo_camera_ip.sql' or success is distinct from true)) then
        raise exception 'Unexpected Flyway V3 state; stop and investigate before repairing';
    end if;
    update ipe_ops.flyway_schema_history set checksum = 1843662674
    where version = '3' and checksum = -834061969
        and script = 'V3__update_demo_camera_ip.sql' and success = true;
end;
$repair$;
commit;
