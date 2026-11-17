-- =============================================================================
-- pep_metering：模型路由目录与 Token 配额账本
--
-- 计量账本是配额余额的唯一权威来源；Trace 与供应商响应只作为对账证据。
-- 账本只追加，计数列与账本在同一事务提交。金额/电量与 Token 分开，Token 用 bigint。
-- =============================================================================
create schema if not exists pep_metering;
set search_path to pep_metering;

create table model_provider (
    id              uuid primary key,
    code            varchar(64) not null unique,
    name            varchar(128) not null,
    base_url        varchar(512) not null,
    credential_cipher bytea,
    credential_nonce  bytea,
    credential_key_id varchar(64),
    state           varchar(16) not null default 'ACTIVE',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create table model_profile (
    id                uuid primary key,
    key               varchar(64) not null unique,
    display_name      varchar(128) not null,
    provider_id       uuid not null,
    upstream_model    varchar(128) not null,
    context_window    integer,
    max_output_tokens integer not null default 2048,
    token_weight_num  integer not null default 1,
    token_weight_den  integer not null default 1,
    counting_mode     varchar(32) not null default 'CONSERVATIVE_ESTIMATE',
    state             varchar(16) not null default 'ACTIVE',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create table quota_policy_version (
    id                uuid primary key,
    policy_key        varchar(64) not null,
    revision          bigint not null,
    mode              varchar(32) not null,
    limit_units       bigint not null,
    duration_seconds  bigint,
    effective_at      timestamptz not null default now(),
    expires_at        timestamptz,
    unit_rule_version varchar(32) not null default 'v1',
    created_at        timestamptz not null default now(),
    unique (policy_key, revision),
    check (limit_units >= 0),
    check ((mode = 'TOTAL' and duration_seconds is null)
        or (mode = 'ON_FIRST_USE_WINDOW' and duration_seconds > 0)),
    check (expires_at is null or expires_at > effective_at)
);

create table quota_account (
    id                uuid primary key,
    scope_kind        varchar(32) not null,
    subject_id        varchar(128) not null,
    policy_key        varchar(64) not null,
    active_revision   bigint not null,
    current_bucket_id uuid,
    state             varchar(16) not null default 'ACTIVE',
    debt_units        bigint not null default 0,
    version           bigint not null default 0,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    unique (scope_kind, subject_id, policy_key),
    check (scope_kind in ('USER','SERVICE_ACCOUNT','PROJECT','TENANT')),
    check (debt_units >= 0)
);

create table quota_bucket (
    id                uuid primary key,
    account_id        uuid not null,
    policy_revision   bigint not null,
    generation        bigint not null,
    start_at          timestamptz not null,
    end_at            timestamptz,
    activation_state  varchar(16) not null,
    limit_units       bigint not null,
    used_units        bigint not null default 0,
    held_units        bigint not null default 0,
    created_at        timestamptz not null default now(),
    unique (account_id, generation),
    check (used_units >= 0),
    check (held_units >= 0),
    check (end_at is null or end_at > start_at)
);

create table model_call (
    id                 uuid primary key,
    logical_call_id    uuid not null,
    attempt_no         integer not null,
    billing_subject_id varchar(128) not null,
    scope_kind         varchar(32) not null,
    actor_id           varchar(128),
    tenant_id          varchar(64),
    project_id         varchar(64),
    task_id            varchar(64),
    run_id             varchar(64),
    model_profile_key  varchar(64) not null,
    provider           varchar(64) not null,
    model              varchar(128) not null,
    request_hash       varchar(64) not null,
    provider_request_id varchar(128),
    state              varchar(16) not null,
    dispatched_at      timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    unique (logical_call_id, attempt_no)
);
create index ix_model_call_subject on model_call (billing_subject_id, created_at desc, id);
create index ix_model_call_project on model_call (project_id, created_at desc, id);

create table quota_reservation (
    id                uuid primary key,
    model_call_id     uuid not null unique,
    reserved_units    bigint not null,
    state             varchar(16) not null,
    dispatch_deadline timestamptz not null,
    dispatched_at     timestamptz,
    version           bigint not null default 0,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    check (reserved_units >= 0)
);

create table reservation_charge (
    id             uuid primary key,
    reservation_id uuid not null,
    account_id     uuid not null,
    bucket_id      uuid not null,
    reserved_units bigint not null,
    settled_units  bigint,
    state          varchar(16) not null,
    created_at     timestamptz not null default now(),
    unique (reservation_id, account_id),
    check (reserved_units >= 0),
    check (settled_units is null or settled_units >= 0)
);
create index ix_reservation_charge_bucket on reservation_charge (bucket_id, state);

create table model_usage (
    id                  uuid primary key,
    model_call_id       uuid not null unique,
    normalization_version varchar(32) not null,
    input_tokens        bigint not null,
    output_tokens       bigint not null,
    cache_read_tokens   bigint not null default 0,
    cache_write_tokens  bigint not null default 0,
    reasoning_tokens    bigint not null default 0,
    quota_units         bigint not null,
    cost_amount         numeric(18, 6),
    currency            varchar(8),
    quality             varchar(16) not null,
    provider_usage_hash varchar(64),
    raw_usage           jsonb,
    created_at          timestamptz not null default now(),
    check (input_tokens >= 0 and output_tokens >= 0 and quota_units >= 0)
);

create table quota_ledger_entry (
    id             uuid primary key,
    account_id     uuid not null,
    bucket_id      uuid,
    reservation_id uuid,
    event_type     varchar(32) not null,
    delta_used     bigint not null default 0,
    delta_held     bigint not null default 0,
    source_event_id varchar(128) not null,
    created_at     timestamptz not null default now(),
    unique (account_id, source_event_id)
);
create index ix_ledger_account_time on quota_ledger_entry (account_id, created_at desc, id);

create table usage_adjustment (
    id              uuid primary key,
    model_call_id   uuid not null,
    adjustment_no   integer not null,
    delta_units     bigint not null,
    reason          varchar(512) not null,
    evidence_ref    varchar(512),
    operator_id     varchar(128) not null,
    idempotency_key varchar(128) not null,
    created_at      timestamptz not null default now(),
    unique (model_call_id, adjustment_no),
    unique (idempotency_key)
);

create table quota_change_request (
    id              uuid primary key,
    account_id      uuid not null,
    old_revision    bigint,
    new_revision    bigint,
    action          varchar(32) not null,
    effective_mode  varchar(32) not null,
    reason          varchar(512) not null,
    actor_id        varchar(128) not null,
    idempotency_key varchar(128) not null,
    created_at      timestamptz not null default now(),
    unique (actor_id, idempotency_key)
);

-- 演示用默认额度：管理员可在控制台调整
insert into quota_policy_version (id, policy_key, revision, mode, limit_units, duration_seconds)
values ('20000000-0000-0000-0000-000000000001', 'demo-window-5h', 1, 'ON_FIRST_USE_WINDOW', 100000000, 18000);
