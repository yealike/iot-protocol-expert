create sequence if not exists ai_agent_release_cursor_seq;

alter table ai_agent_release add column if not exists release_cursor bigint;

update ai_agent_release
set release_cursor = nextval('ai_agent_release_cursor_seq')
where release_cursor is null;

alter table ai_agent_release alter column release_cursor set default nextval('ai_agent_release_cursor_seq');
alter table ai_agent_release alter column release_cursor set not null;

create unique index if not exists uq_ai_agent_release_cursor
    on ai_agent_release (release_cursor);
