-- =============================================================================
-- pep_platform：应用账号、租户/项目、角色绑定、服务账号与审计
--
-- 认证身份由 (issuer, subject) 唯一确定；本地登录只是 issuer=local 的一种适配。
-- 角色绑定携带作用域，页面菜单和角色码不作为资源访问许可。
-- =============================================================================
set search_path to pep_platform;

create table tenant (
    id         uuid primary key,
    code       varchar(64) not null unique,
    name       varchar(128) not null,
    state      varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table project (
    id         uuid primary key,
    tenant_id  uuid not null,
    code       varchar(64) not null,
    name       varchar(128) not null,
    state      varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create index ix_project_tenant on project (tenant_id, created_at, id);

-- 旧账号表整体迁入平台层，保留历史密码哈希以支持本地演示登录
alter table pep_domain.ops_user set schema pep_platform;
alter table pep_platform.ops_user rename to app_user;
alter table app_user add column issuer varchar(128) not null default 'local';
alter table app_user add column subject varchar(191);
alter table app_user add column display_name varchar(128);
alter table app_user add column state varchar(16) not null default 'ACTIVE';
alter table app_user add column tenant_id uuid;
alter table app_user add column updated_at timestamptz not null default now();
alter table app_user add column last_login_at timestamptz;
update app_user set subject = username;
update app_user set display_name = username where display_name is null;
update app_user set state = case when enabled then 'ACTIVE' else 'DISABLED' end;
alter table app_user alter column subject set not null;
create unique index ux_app_user_issuer_subject on app_user (issuer, subject);
create index ix_app_user_tenant on app_user (tenant_id, created_at, id);

create table tenant_member (
    tenant_id  uuid not null,
    user_id    bigint not null,
    state      varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    primary key (tenant_id, user_id)
);

create table project_member (
    project_id uuid not null,
    user_id    bigint not null,
    state      varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    primary key (project_id, user_id)
);

create table role (
    id         uuid primary key,
    code       varchar(64) not null,
    name       varchar(64) not null,
    scope_type varchar(16) not null,
    built_in   boolean not null default false,
    created_at timestamptz not null default now(),
    unique (scope_type, code)
);

create table role_permission (
    role_id         uuid not null,
    permission_code varchar(64) not null,
    primary key (role_id, permission_code)
);

create table role_binding (
    id             uuid primary key,
    principal_type varchar(16) not null,
    principal_id   varchar(64) not null,
    role_id        uuid not null,
    scope_type     varchar(16) not null,
    scope_id       varchar(64) not null,
    created_at     timestamptz not null default now(),
    unique (principal_type, principal_id, role_id, scope_type, scope_id)
);
create index ix_role_binding_principal on role_binding (principal_type, principal_id);
create index ix_role_binding_scope on role_binding (scope_type, scope_id);

create table user_station_scope (
    user_id    bigint not null,
    station_id bigint not null,
    primary key (user_id, station_id)
);

create table service_account (
    id         uuid primary key,
    project_id uuid not null,
    code       varchar(64) not null,
    name       varchar(128) not null,
    state      varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (project_id, code)
);

create table access_key (
    id             uuid primary key,
    principal_type varchar(16) not null,
    principal_id   varchar(64) not null,
    name           varchar(64) not null,
    key_hash       varchar(128) not null unique,
    key_prefix     varchar(16) not null,
    permissions    jsonb not null default '[]'::jsonb,
    expires_at     timestamptz,
    state          varchar(16) not null default 'ACTIVE',
    last_used_at   timestamptz,
    created_at     timestamptz not null default now(),
    unique (principal_type, principal_id, name)
);

create table resource_grant (
    id               uuid primary key,
    owner_project_id uuid not null,
    resource_type    varchar(32) not null,
    resource_id      varchar(64) not null,
    grantee_type     varchar(16) not null,
    grantee_id       varchar(64) not null,
    action           varchar(32) not null,
    expires_at       timestamptz,
    created_at       timestamptz not null default now(),
    unique (resource_type, resource_id, grantee_type, grantee_id, action)
);

create table audit_event (
    id          uuid primary key,
    actor_id    varchar(64),
    subject_id  varchar(64),
    tenant_id   uuid,
    project_id  uuid,
    action      varchar(64) not null,
    target_type varchar(64),
    target_id   varchar(128),
    result      varchar(16) not null,
    reason      varchar(512),
    request_id  varchar(128),
    detail      jsonb,
    created_at  timestamptz not null default now()
);
create index ix_audit_event_scope on audit_event (tenant_id, project_id, created_at desc, id);
create index ix_audit_event_target on audit_event (target_type, target_id, created_at desc);

-- ---------------------------------------------------------------- 种子数据
insert into tenant (id, code, name) values
 ('00000000-0000-0000-0000-000000000001', 'personal', '个人租户');
insert into project (id, tenant_id, code, name) values
 ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'default', '默认项目');
update app_user
   set tenant_id = '00000000-0000-0000-0000-000000000001'
 where tenant_id is null;

insert into role (id, code, name, scope_type, built_in) values
 ('10000000-0000-0000-0000-000000000001', 'PLATFORM_ADMIN', '平台管理员', 'PLATFORM', true),
 ('10000000-0000-0000-0000-000000000002', 'TENANT_OWNER', '租户所有者', 'TENANT', true),
 ('10000000-0000-0000-0000-000000000003', 'PROJECT_ADMIN', '项目管理员', 'PROJECT', true),
 ('10000000-0000-0000-0000-000000000004', 'OPERATOR', '运维人员', 'PROJECT', true),
 ('10000000-0000-0000-0000-000000000005', 'ANALYST', '分析人员', 'PROJECT', true),
 ('10000000-0000-0000-0000-000000000006', 'VIEWER', '只读人员', 'PROJECT', true),
 ('10000000-0000-0000-0000-000000000007', 'QUOTA_ADMIN', '额度管理员', 'PLATFORM', true),
 ('10000000-0000-0000-0000-000000000008', 'AUDITOR', '审计人员', 'PLATFORM', true);

insert into role_permission (role_id, permission_code) values
 ('10000000-0000-0000-0000-000000000001', 'iam:user:manage'),
 ('10000000-0000-0000-0000-000000000001', 'platform:admin'),
 ('10000000-0000-0000-0000-000000000001', 'ops:read'),
 ('10000000-0000-0000-0000-000000000001', 'ops:operate'),
 ('10000000-0000-0000-0000-000000000001', 'ops:manage'),
 ('10000000-0000-0000-0000-000000000001', 'ai:use'),
 ('10000000-0000-0000-0000-000000000001', 'ai:admin'),
 ('10000000-0000-0000-0000-000000000001', 'quota:grant'),
 ('10000000-0000-0000-0000-000000000001', 'usage:read'),
 ('10000000-0000-0000-0000-000000000001', 'audit:read'),
 ('10000000-0000-0000-0000-000000000002', 'tenant:manage'),
 ('10000000-0000-0000-0000-000000000002', 'project:create'),
 ('10000000-0000-0000-0000-000000000002', 'member:manage'),
 ('10000000-0000-0000-0000-000000000002', 'quota:grant'),
 ('10000000-0000-0000-0000-000000000002', 'usage:read'),
 ('10000000-0000-0000-0000-000000000002', 'ops:read'),
 ('10000000-0000-0000-0000-000000000002', 'investigation:read'),
 ('10000000-0000-0000-0000-000000000003', 'project:manage'),
 ('10000000-0000-0000-0000-000000000003', 'member:manage'),
 ('10000000-0000-0000-0000-000000000003', 'agent:publish'),
 ('10000000-0000-0000-0000-000000000003', 'device:manage'),
 ('10000000-0000-0000-0000-000000000003', 'ops:read'),
 ('10000000-0000-0000-0000-000000000003', 'ops:operate'),
 ('10000000-0000-0000-0000-000000000003', 'ai:use'),
 ('10000000-0000-0000-0000-000000000003', 'investigation:create'),
 ('10000000-0000-0000-0000-000000000003', 'investigation:read'),
 ('10000000-0000-0000-0000-000000000003', 'investigation:operate'),
 ('10000000-0000-0000-0000-000000000003', 'simulation:run'),
 ('10000000-0000-0000-0000-000000000004', 'device:command:start'),
 ('10000000-0000-0000-0000-000000000004', 'ops:read'),
 ('10000000-0000-0000-0000-000000000004', 'ops:operate'),
 ('10000000-0000-0000-0000-000000000004', 'ai:use'),
 ('10000000-0000-0000-0000-000000000004', 'investigation:create'),
 ('10000000-0000-0000-0000-000000000004', 'investigation:read'),
 ('10000000-0000-0000-0000-000000000004', 'investigation:operate'),
 ('10000000-0000-0000-0000-000000000004', 'simulation:run'),
 ('10000000-0000-0000-0000-000000000005', 'ops:read'),
 ('10000000-0000-0000-0000-000000000005', 'ai:use'),
 ('10000000-0000-0000-0000-000000000005', 'investigation:create'),
 ('10000000-0000-0000-0000-000000000005', 'investigation:read'),
 ('10000000-0000-0000-0000-000000000005', 'simulation:run'),
 ('10000000-0000-0000-0000-000000000006', 'ops:read'),
 ('10000000-0000-0000-0000-000000000006', 'investigation:read'),
 ('10000000-0000-0000-0000-000000000007', 'quota:grant'),
 ('10000000-0000-0000-0000-000000000007', 'usage:read'),
 ('10000000-0000-0000-0000-000000000008', 'audit:read'),
 ('10000000-0000-0000-0000-000000000008', 'usage:read');

-- 旧角色到新角色的一次性映射
insert into role_binding (id, principal_type, principal_id, role_id, scope_type, scope_id)
select gen_random_uuid(),
       'USER',
       ur.user_id::text,
       r.id,
       case when r.code = 'PLATFORM_ADMIN' then 'PLATFORM' else 'PROJECT' end,
       case when r.code = 'PLATFORM_ADMIN' then '*'
            else '00000000-0000-0000-0000-000000000002' end
  from pep_domain.iam_user_role ur
  join role r on r.code = case ur.role_code
        when 'ADMIN' then 'PLATFORM_ADMIN'
        when 'OPS' then 'OPERATOR'
        else 'VIEWER' end
 where not exists (
       select 1 from role_binding b
        where b.principal_type = 'USER' and b.principal_id = ur.user_id::text
          and b.role_id = r.id);

-- 演示管理员同时获得默认项目的项目管理员角色，便于使用调查任务等业务能力；
-- 平台角色本身不默认读取业务原文。
insert into role_binding (id, principal_type, principal_id, role_id, scope_type, scope_id)
select gen_random_uuid(),
       'USER',
       ur.user_id::text,
       r.id,
       'PROJECT',
       '00000000-0000-0000-0000-000000000002'
  from pep_domain.iam_user_role ur
  join role r on r.code = 'PROJECT_ADMIN'
 where ur.role_code = 'ADMIN'
   and not exists (
       select 1 from role_binding b
        where b.principal_type = 'USER' and b.principal_id = ur.user_id::text
          and b.role_id = r.id and b.scope_type = 'PROJECT'
          and b.scope_id = '00000000-0000-0000-0000-000000000002');

insert into user_station_scope (user_id, station_id)
select user_id, station_id from pep_domain.iam_user_station;

insert into tenant_member (tenant_id, user_id)
select '00000000-0000-0000-0000-000000000001', id from app_user;
insert into project_member (project_id, user_id)
select '00000000-0000-0000-0000-000000000002', id from app_user;

drop table pep_domain.iam_user_role;
drop table pep_domain.iam_user_station;
drop table pep_domain.iam_role_permission;
drop table pep_domain.iam_role;

