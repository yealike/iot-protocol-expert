create table iam_role (
    code varchar(32) primary key,
    name varchar(64) not null
);
create table iam_role_permission (
    role_code varchar(32) not null references iam_role(code),
    permission varchar(64) not null,
    primary key (role_code, permission)
);
create table iam_user_role (
    user_id bigint not null references ops_user(id) on delete cascade,
    role_code varchar(32) not null references iam_role(code),
    primary key (user_id, role_code)
);
create table iam_user_station (
    user_id bigint not null references ops_user(id) on delete cascade,
    station_id bigint not null references station(id),
    primary key (user_id, station_id)
);
alter table ops_user add column all_stations boolean not null default false;
alter table ops_user add column auth_version bigint not null default 0;
insert into iam_role(code, name) values ('ADMIN','管理员'), ('OPS','运营人员'), ('VIEWER','查看人员');
insert into iam_user_role(user_id, role_code) select id, role from ops_user;
update ops_user set all_stations = true;
insert into iam_role_permission(role_code, permission) values
 ('ADMIN','iam:user:manage'), ('ADMIN','ops:manage'), ('ADMIN','ops:operate'), ('ADMIN','ops:read'), ('ADMIN','ai:use'), ('ADMIN','ai:admin'),
 ('OPS','ops:operate'), ('OPS','ops:read'), ('OPS','ai:use'),
 ('VIEWER','ops:read'), ('VIEWER','ai:use');
alter table ops_user drop column role;
