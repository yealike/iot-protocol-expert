alter table pep_metering.model_call
    add column root_run_id varchar(64),
    add column parent_run_id varchar(64),
    add column a2a_task_id varchar(64),
    add column estimated_tokens bigint not null default 0;

create index ix_model_call_root_run on pep_metering.model_call (root_run_id, created_at desc, id);
