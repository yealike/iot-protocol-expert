alter table ai_mcp_component add column if not exists publication_generation bigint not null default 0;
alter table ai_mcp_release add column if not exists generation bigint not null default 0;
alter table ai_skill_release add column if not exists generation bigint not null default 0;
alter table ai_skill_component add column if not exists publication_generation bigint not null default 0;
alter table ai_agent_component add column if not exists publication_generation bigint not null default 0;

-- 存量数据初始化：以组件当前默认版本对应的 release generation 为默认发布代次。
update ai_mcp_component c
   set publication_generation = coalesce((
       select r.generation from ai_mcp_release r
        where r.component_id = c.id and r.version = c.published_version
   ), 0);
update ai_skill_component c
   set publication_generation = coalesce((
       select r.generation from ai_skill_release r
        where r.skill_id = c.id and r.version = c.published_version
   ), 0);
update ai_agent_component c
   set publication_generation = coalesce((
       select r.generation from ai_agent_release r
        where r.agent_id = c.id and r.version = c.published_version
   ), 0);
