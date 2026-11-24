set search_path to pep_agent;

alter table ai_conversation
    add column root_conversation_id uuid,
    add column parent_conversation_id uuid,
    add column conversation_kind varchar(24) not null default 'USER',
    add constraint ck_ai_conversation_kind
        check ((conversation_kind = 'USER' and parent_conversation_id is null)
            or (conversation_kind = 'A2A_CHILD' and parent_conversation_id is not null
                and root_conversation_id is not null));

create index ix_ai_conversation_kind on ai_conversation (user_id, conversation_kind, updated_at desc);

alter table ai_run
    add column parent_run_id uuid,
    add column root_run_id uuid,
    add column delegation_depth int not null default 0,
    add column a2a_task_id uuid,
    add column a2a_context_id varchar(128),
    add column dispatch_mode varchar(24) not null default 'CONTROL_PUSH',
    add constraint ck_ai_run_dispatch_mode
        check ((dispatch_mode = 'CONTROL_PUSH' and parent_run_id is null and delegation_depth = 0)
            or (dispatch_mode = 'PARENT_LOCAL' and parent_run_id is not null and root_run_id is not null
                and a2a_task_id is not null and delegation_depth between 1 and 3));

create index ix_ai_run_root on ai_run (root_run_id, created_at desc);
create index ix_ai_run_parent on ai_run (parent_run_id, status);
create unique index uq_ai_run_a2a_task on ai_run (a2a_task_id) where a2a_task_id is not null;

alter table ai_agent_component
    add column capability_tags jsonb not null default '[]'::jsonb,
    add column a2a_capabilities jsonb not null default '[]'::jsonb,
    add column delegation_policy jsonb;

create index ix_ai_agent_component_capability_tags
    on ai_agent_component using gin (capability_tags jsonb_path_ops);

create table ai_agent_delegate_binding (
    id uuid primary key,
    agent_id uuid not null,
    alias varchar(40) not null,
    target_agent_id uuid not null,
    target_release_id uuid not null,
    target_version varchar(32) not null,
    target_manifest_digest char(64) not null,
    description varchar(512) not null,
    when_to_use varchar(1024),
    allowed_tools jsonb not null default '[]'::jsonb,
    required boolean not null default false,
    max_calls int not null,
    timeout_ms int not null,
    order_no int not null,
    created_at timestamptz not null default now(),
    constraint uq_ai_agent_delegate_binding unique (agent_id, alias)
);

create index ix_ai_agent_delegate_target on ai_agent_delegate_binding (target_agent_id);

create table ai_a2a_task (
    id uuid primary key,
    root_run_id uuid not null,
    parent_run_id uuid not null,
    child_run_id uuid not null unique,
    child_conversation_id uuid not null,
    context_id varchar(128) not null,
    source_agent_id uuid not null,
    source_release_id uuid not null,
    target_agent_id uuid not null,
    target_release_id uuid not null,
    delegate_alias varchar(40) not null,
    depth int not null check (depth between 1 and 3),
    mode varchar(16) not null default 'SYNC' check (mode = 'SYNC'),
    attempt_no int not null default 1,
    state varchar(24) not null,
    idempotency_key varchar(128) not null,
    request_digest char(64) not null,
    request_json jsonb not null,
    result_json jsonb not null default '{}'::jsonb,
    usage_json jsonb not null default '{}'::jsonb,
    error_code varchar(64),
    error_message text,
    deadline_at timestamptz not null,
    cancel_requested_at timestamptz,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint uq_ai_a2a_task_key unique (parent_run_id, idempotency_key),
    constraint ck_ai_a2a_task_state check (
        state in ('SUBMITTED', 'WORKING', 'CANCEL_REQUESTED', 'COMPLETED', 'FAILED', 'CANCELED', 'REJECTED')
    )
);

create index ix_ai_a2a_task_request_digest on ai_a2a_task (parent_run_id, request_digest);
create index ix_ai_a2a_task_root on ai_a2a_task (root_run_id, state, created_at desc);
create index ix_ai_a2a_task_target on ai_a2a_task (target_release_id, state);
create index ix_ai_a2a_task_context on ai_a2a_task (context_id);

create table ai_a2a_task_event (
    id uuid primary key,
    task_id uuid not null,
    seq bigint not null,
    type varchar(48) not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint uq_ai_a2a_task_event unique (task_id, seq)
);
