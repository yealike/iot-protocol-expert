-- Agent evaluation datasets, runs, results and scores.
-- Relationships stay application-managed, following the repository's no-physical-FK convention.

alter table ai_conversation
    add column if not exists source varchar(24) not null default 'USER';

create index if not exists ix_ai_conversation_user_source_updated
    on ai_conversation (user_id, source, updated_at desc);

create table if not exists ai_eval_dataset (
    id uuid primary key,
    dataset_key varchar(100) not null,
    name varchar(200) not null,
    description text,
    status varchar(24) not null default 'DRAFT',
    revision bigint not null default 1,
    owner_id varchar(200) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_ai_eval_dataset_owner_key unique (owner_id, dataset_key),
    constraint ck_ai_eval_dataset_status check (status in ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

create index if not exists ix_ai_eval_dataset_owner_status_updated
    on ai_eval_dataset (owner_id, status, updated_at desc);

create table if not exists ai_eval_case (
    id uuid primary key,
    dataset_id uuid not null,
    case_key varchar(120) not null,
    input_json jsonb not null,
    expected_output_json jsonb,
    assertions_json jsonb not null default '{}'::jsonb,
    tags_json jsonb not null default '[]'::jsonb,
    weight numeric(8,4) not null default 1,
    enabled boolean not null default true,
    revision bigint not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_ai_eval_case_dataset_key unique (dataset_id, case_key),
    constraint ck_ai_eval_case_weight check (weight > 0)
);

create index if not exists ix_ai_eval_case_dataset_enabled
    on ai_eval_case (dataset_id, enabled, case_key);

create table if not exists ai_eval_run (
    id uuid primary key,
    mode varchar(24) not null,
    dataset_id uuid,
    dataset_revision bigint,
    agent_key varchar(100) not null,
    agent_version varchar(64) not null,
    agent_release_id uuid not null,
    manifest_digest varchar(128) not null,
    evaluator_snapshot jsonb not null default '[]'::jsonb,
    status varchar(32) not null default 'QUEUED',
    requested_by varchar(200) not null,
    requested_roles jsonb not null default '[]'::jsonb,
    requested_permissions jsonb not null default '[]'::jsonb,
    requested_auth_version bigint not null default 0,
    total_items integer not null default 0,
    completed_items integer not null default 0,
    failed_items integer not null default 0,
    config_json jsonb not null default '{}'::jsonb,
    summary_json jsonb not null default '{}'::jsonb,
    error_code varchar(100),
    error_message text,
    lease_owner varchar(200),
    lease_until timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_ai_eval_run_mode check (mode in ('ONLINE', 'DATASET')),
    constraint ck_ai_eval_run_status check (status in (
        'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED'))
);

create index if not exists ix_ai_eval_run_owner_created
    on ai_eval_run (requested_by, created_at desc);
create index if not exists ix_ai_eval_run_status_lease
    on ai_eval_run (status, lease_until, created_at);
create index if not exists ix_ai_eval_run_dataset_created
    on ai_eval_run (dataset_id, created_at desc);

create table if not exists ai_eval_result (
    id uuid primary key,
    eval_run_id uuid not null,
    case_id uuid,
    case_revision bigint,
    conversation_id uuid,
    run_id uuid,
    trace_id varchar(64),
    root_span_id varchar(32),
    final_status varchar(32),
    input_snapshot jsonb not null default '{}'::jsonb,
    expected_snapshot jsonb,
    output_snapshot jsonb,
    duration_ms bigint,
    input_tokens bigint,
    output_tokens bigint,
    estimated_cost numeric(18,8),
    status varchar(24) not null default 'QUEUED',
    error_code varchar(100),
    error_message text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_ai_eval_result_run_case unique (eval_run_id, case_id),
    constraint uq_ai_eval_result_source_run unique (run_id),
    constraint ck_ai_eval_result_status check (status in (
        'QUEUED', 'EXECUTING', 'EVALUATING', 'COMPLETED', 'FAILED', 'SKIPPED'))
);

create index if not exists ix_ai_eval_result_eval_run_status
    on ai_eval_result (eval_run_id, status, created_at);
create index if not exists ix_ai_eval_result_trace
    on ai_eval_result (trace_id) where trace_id is not null;

create table if not exists ai_eval_score (
    id uuid primary key,
    result_id uuid not null,
    evaluator_key varchar(100) not null,
    evaluator_version bigint not null default 1,
    scope varchar(24) not null,
    trace_id varchar(64),
    span_id varchar(32),
    metric_key varchar(100) not null,
    score_type varchar(24) not null,
    score_number numeric(12,6),
    score_boolean boolean,
    score_label varchar(200),
    score_text text,
    reason text,
    evidence_json jsonb,
    source varchar(24) not null,
    status varchar(24) not null default 'ACTIVE',
    reviewer_id varchar(200),
    created_at timestamptz not null default now(),
    constraint ck_ai_eval_score_scope check (scope in ('RUN', 'TRACE', 'SPAN')),
    constraint ck_ai_eval_score_type check (score_type in ('BOOLEAN', 'NUMERIC', 'CATEGORICAL', 'TEXT')),
    constraint ck_ai_eval_score_source check (source in ('RULE', 'LLM_JUDGE', 'HUMAN', 'USER_FEEDBACK')),
    constraint ck_ai_eval_score_status check (status in ('ACTIVE', 'SUPERSEDED'))
);

create unique index if not exists uq_ai_eval_score_active_metric
    on ai_eval_score (result_id, evaluator_key, evaluator_version, scope, coalesce(span_id, ''), metric_key)
    where status = 'ACTIVE';
create index if not exists ix_ai_eval_score_result
    on ai_eval_score (result_id, status, created_at);
