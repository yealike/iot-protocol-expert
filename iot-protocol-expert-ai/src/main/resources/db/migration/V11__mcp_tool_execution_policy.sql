create table if not exists ai_mcp_tool_policy (
    mcp_id uuid not null,
    tool_name varchar(128) not null,
    execution_category varchar(16) not null default 'UNKNOWN',
    reviewed_by varchar(128) not null,
    reviewed_at timestamptz not null default now(),
    constraint pk_ai_mcp_tool_policy primary key (mcp_id, tool_name),
    constraint ck_ai_mcp_tool_execution_category
        check (execution_category in ('READ_ONLY', 'WRITE', 'UNKNOWN'))
);

create index if not exists ix_ai_mcp_tool_policy_category
    on ai_mcp_tool_policy (mcp_id, execution_category);
