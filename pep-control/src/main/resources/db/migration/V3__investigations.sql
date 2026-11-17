-- =============================================================================
-- pep_agent：调查任务、证据、假设、实验引用与结论
--
-- 任务与 Run 分离；证据是不可变快照，结论按版本追加。状态跃迁写入 task_event，
-- 由应用服务在任务行锁内分配 task_event.seq，作为前端续传游标。
-- =============================================================================
set search_path to pep_agent;

create table investigation_task (
    id          uuid primary key,
    tenant_id   uuid not null,
    project_id  uuid not null,
    owner_id    varchar(64) not null,
    title       varchar(256) not null,
    goal        text not null,
    scope       jsonb not null default '{}'::jsonb,
    status      varchar(32) not null,
    version     bigint not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create index ix_investigation_task_owner on investigation_task (project_id, owner_id, updated_at desc, id);
create index ix_investigation_task_status on investigation_task (project_id, status, updated_at desc, id);

create table task_event (
    id         uuid primary key,
    task_id    uuid not null,
    seq        bigint not null,
    type       varchar(64) not null,
    payload    jsonb not null default '{}'::jsonb,
    actor_id   varchar(64),
    created_at timestamptz not null default now(),
    unique (task_id, seq)
);

create table evidence_item (
    id              uuid primary key,
    tenant_id       uuid not null,
    project_id      uuid not null,
    source_type     varchar(32) not null,
    source_id       varchar(128) not null,
    source_version  varchar(64),
    content_hash    varchar(64) not null,
    summary         text not null,
    raw_ref         varchar(512),
    scope           jsonb not null default '{}'::jsonb,
    collected_at    timestamptz not null,
    expires_at      timestamptz,
    metadata        jsonb not null default '{}'::jsonb,
    created_at      timestamptz not null default now()
);
create index ix_evidence_project on evidence_item (project_id, source_type, collected_at desc, id);

create table task_evidence (
    task_id     uuid not null,
    evidence_id uuid not null,
    added_by    varchar(64) not null,
    created_at  timestamptz not null default now(),
    primary key (task_id, evidence_id)
);

create table diagnostic_hypothesis (
    id           uuid primary key,
    task_id      uuid not null,
    statement    text not null,
    status       varchar(16) not null default 'OPEN',
    support_refs jsonb not null default '[]'::jsonb,
    created_by   varchar(64) not null,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index ix_hypothesis_task on diagnostic_hypothesis (task_id, created_at, id);

create table task_experiment (
    id                    uuid primary key,
    task_id               uuid not null,
    scenario_release_id   varchar(128) not null,
    sim_run_id            varchar(128),
    status                varchar(16) not null default 'REQUESTED',
    result_summary        text,
    result_ref            varchar(512),
    expected_assertion    jsonb not null default '{}'::jsonb,
    created_by            varchar(64) not null,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);
create index ix_task_experiment_task on task_experiment (task_id, created_at, id);

create table task_conclusion (
    id                uuid primary key,
    task_id           uuid not null,
    version           bigint not null,
    conclusion        text not null,
    evidence_refs     jsonb not null default '[]'::jsonb,
    validation_status varchar(16) not null,
    created_by        varchar(64) not null,
    created_at        timestamptz not null default now(),
    unique (task_id, version)
);

create table action_approval (
    id           uuid primary key,
    task_id      uuid not null,
    action_type  varchar(64) not null,
    target       jsonb not null,
    param_hash   varchar(64) not null,
    approved_by  varchar(64) not null,
    expires_at   timestamptz not null,
    used_at      timestamptz,
    state        varchar(16) not null default 'PENDING',
    created_at   timestamptz not null default now()
);
create index ix_action_approval_task on action_approval (task_id, created_at desc, id);

