-- 多 Control 实例下的发布协调租约：同一 publication 同一时刻只由一个 worker 推进。
alter table ai_publication add column if not exists lease_owner varchar(128);
alter table ai_publication add column if not exists lease_until timestamptz;

create index if not exists ix_ai_publication_lease
    on ai_publication (state, next_attempt_at, lease_until);
