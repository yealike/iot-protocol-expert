-- =============================================================================
-- 充电桩专属数据模型
--
-- 本项目是个人演示环境，不迁移历史业务数据。保留已上传协议文档及其向量索引，
-- 清理充电业务运行数据，并把设备身份收敛为 8 位平台 SN。
-- =============================================================================

set search_path to pep_domain;

truncate table
    frame_log,
    command_record,
    device_event_log,
    device_property_latest,
    device_property_ts,
    order_telemetry_outbox,
    bill,
    charge_order,
    device_tag,
    device_channel,
    device_instance,
    trouble_case,
    rate_model
restart identity;

drop table if exists device_category;

-- ---------------------------------------------------------------- 产品

alter table product drop column if exists device_type;
alter table product drop column if exists scene_model;
alter table product
    add column connector_count smallint not null default 2
        check (connector_count between 1 and 99);

delete from product where protocol not in ('YKC16', 'OCPP16J');
update product
set category_code = 'charging',
    connector_count = 2,
    updated_at = now()
where protocol in ('YKC16', 'OCPP16J');

alter table product drop constraint if exists ck_product_protocol;
alter table product
    add constraint ck_product_protocol check (protocol in ('YKC16', 'OCPP16J'));

-- ---------------------------------------------------------------- 设备与充电枪

alter table device_instance drop constraint if exists uq_device_protocol_code;
drop index if exists idx_device_station;
drop index if exists idx_device_type;
drop index if exists idx_device_parent;

alter table device_instance drop column if exists device_type;
alter table device_instance drop column if exists protocol_device_id;
alter table device_instance drop column if exists station_id;
alter table device_instance drop column if exists parent_id;
alter table device_instance drop column if exists derive_metadata;
alter table device_instance drop column if exists scene_anchor;
alter table device_instance alter column device_code type varchar(8);
alter table device_instance drop constraint if exists ck_device_platform_sn;
alter table device_instance
    add constraint ck_device_platform_sn
        check (device_code ~ '^[1-9][0-9]{7}$' and device_code::bigint >= 10000000);
alter table device_instance drop constraint if exists ck_device_protocol;
alter table device_instance
    add constraint ck_device_protocol check (protocol in ('YKC16', 'OCPP16J'));

create sequence if not exists device_sn_seq
    as bigint
    increment by 1
    minvalue 10000000
    maxvalue 99999999
    start with 10000000
    no cycle;

alter table device_channel add column if not exists platform_gun_no varchar(10);
alter table device_channel add column if not exists protocol_connector_no varchar(64);
alter table device_channel drop column if exists channel_type;
alter table device_channel drop column if exists metadata;
alter table device_channel drop constraint if exists ck_device_channel_gun_no;
alter table device_channel
    add constraint ck_device_channel_gun_no check (channel_no ~ '^(0[1-9]|[1-9][0-9])$');

create unique index if not exists uq_device_channel_platform_gun_no
    on device_channel (platform_gun_no)
    where platform_gun_no is not null;
create unique index if not exists uq_device_channel_protocol_connector
    on device_channel (device_id, protocol_connector_no)
    where protocol_connector_no is not null;

create table device_protocol_mapping (
    id                    bigserial primary key,
    device_id             bigint       not null,
    protocol              varchar(24)  not null,
    third_party_device_id varchar(64)  not null,
    enabled               boolean      not null default true,
    created_at            timestamp    not null default now(),
    updated_at            timestamp    not null default now(),
    constraint ck_device_protocol_mapping_protocol check (protocol in ('YKC16', 'OCPP16J')),
    constraint ck_device_protocol_mapping_external check (length(trim(third_party_device_id)) > 0)
);
create unique index uq_device_protocol_mapping_device on device_protocol_mapping (device_id);
create unique index uq_device_protocol_mapping_external
    on device_protocol_mapping (protocol, third_party_device_id);
create index idx_device_protocol_mapping_load on device_protocol_mapping (id);

-- ---------------------------------------------------------------- 演示设备

insert into device_instance
    (device_code, name, product_id, protocol, state, enabled, allow_simulate, allow_work,
     configuration, last_seen, online_at, registry_time, created_at, updated_at)
select '10000000', '云快充双枪演示桩', id,
       'YKC16', 'OFFLINE', true, true, true, '{}'::jsonb, now(), null, now(), now(), now()
from product
where protocol = 'YKC16'
order by id
limit 1;

insert into device_instance
    (device_code, name, product_id, protocol, state, enabled, allow_simulate, allow_work,
     configuration, last_seen, online_at, registry_time, created_at, updated_at)
select '10000001', 'OCPP 双枪演示桩', id,
       'OCPP16J', 'OFFLINE', true, true, true, '{}'::jsonb, now(), null, now(), now(), now()
from product
where protocol = 'OCPP16J'
order by id
limit 1;

insert into device_channel
    (device_id, channel_no, platform_gun_no, protocol_connector_no, name, enabled, created_at, updated_at)
select device.id, gun.gun_no, device.device_code || gun.gun_no, gun.connector_no,
       '充电枪 ' || gun.gun_no, true, now(), now()
from device_instance device
cross join (values ('01', '1'), ('02', '2')) as gun(gun_no, connector_no)
where device.device_code in ('10000000', '10000001');

insert into device_protocol_mapping
    (device_id, protocol, third_party_device_id, enabled, created_at, updated_at)
select id, protocol,
       case when protocol = 'YKC16' then '32010600000001' else '32010600000002' end,
       true, now(), now()
from device_instance
where device_code in ('10000000', '10000001');

select setval(
    'device_sn_seq',
    (select max(device_code::bigint) from device_instance),
    true);

-- ---------------------------------------------------------------- 费率

insert into rate_model
    (model_no, sharp_energy, sharp_service, peak_energy, peak_service,
     flat_energy, flat_service, valley_energy, valley_service,
     loss_ratio, segments, active, updated_at)
values
    ('0001', 1.20000, 0.40000, 1.00000, 0.35000,
     0.80000, 0.30000, 0.50000, 0.20000, 0,
     '3,3,3,3,3,3,3,3,3,3,3,3,2,2,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,2,2,2,2,2,2,0,0,0,0,0,0,2,2,2,2,2,2',
     true, now());

create unique index if not exists uq_rate_model_single_active
    on rate_model (active)
    where active;

-- ---------------------------------------------------------------- 平台订单

drop index if exists idx_order_station_time;
drop index if exists idx_order_protocol_tx;
drop index if exists idx_order_ocpp_tx;
drop index if exists uq_charge_order_device_ocpp_transaction;

alter table charge_order drop column if exists station_id;
alter table charge_order drop column if exists protocol_transaction_id;
alter table charge_order drop column if exists ocpp_transaction_id;
alter table charge_order
    add column device_sn varchar(8),
    add column platform_gun_no varchar(10),
    add column electricity_fee numeric(16, 4),
    add column service_fee numeric(16, 4),
    add column loss_energy numeric(16, 4),
    add column rate_model_no varchar(8),
    add column rate_snapshot jsonb,
    add column fee_breakdown jsonb;
alter table charge_order
    alter column device_sn set not null,
    alter column platform_gun_no set not null;
alter table charge_order drop constraint if exists ck_charge_order_gun_no;
alter table charge_order
    add constraint ck_charge_order_gun_no check (gun_no ~ '^(0[1-9]|[1-9][0-9])$');
alter table charge_order drop constraint if exists ck_charge_order_platform_no;
alter table charge_order
    add constraint ck_charge_order_platform_no check (order_no ~ '^[0-9]{21}$');

create index if not exists idx_order_device_sn on charge_order (device_sn);
create index if not exists idx_order_platform_gun on charge_order (platform_gun_no);

create table order_external_reference (
    id                 bigserial primary key,
    order_id           bigint      not null,
    device_id          bigint      not null,
    protocol           varchar(24) not null,
    reference_type     varchar(32) not null,
    external_reference varchar(64) not null,
    created_at         timestamp   not null default now(),
    constraint ck_order_external_reference_protocol check (protocol in ('YKC16', 'OCPP16J')),
    constraint ck_order_external_reference_type
        check (reference_type in ('YKC_TRANSACTION', 'OCPP_TRANSACTION'))
);
create unique index uq_order_external_reference_external
    on order_external_reference (device_id, protocol, reference_type, external_reference);
create unique index uq_order_external_reference_order
    on order_external_reference (order_id, protocol, reference_type);
create index idx_order_external_reference_order on order_external_reference (order_id);

create sequence if not exists ocpp_transaction_seq
    as bigint
    increment by 1
    minvalue 1000
    maxvalue 2147483647
    start with 1000
    no cycle;

create table charge_order_segment (
    id                bigserial primary key,
    order_id          bigint        not null,
    period            smallint      not null check (period between 0 and 3),
    energy            numeric(16, 4) not null default 0,
    loss_energy       numeric(16, 4) not null default 0,
    electricity_price numeric(12, 5) not null default 0,
    service_price     numeric(12, 5) not null default 0,
    electricity_fee   numeric(16, 4) not null default 0,
    service_fee       numeric(16, 4) not null default 0,
    total_amount      numeric(16, 4) not null default 0,
    source            varchar(24)    not null,
    created_at        timestamp      not null default now(),
    constraint ck_charge_order_segment_source
        check (source in ('DEVICE_BILL', 'PLATFORM_ESTIMATE'))
);
create unique index uq_charge_order_segment_period
    on charge_order_segment (order_id, period);

-- ---------------------------------------------------------------- 账单

drop index if exists idx_bill_station_time;
alter table bill drop column if exists station_id;
alter table bill
    add column electricity_fee numeric(16, 4),
    add column service_fee numeric(16, 4),
    add column loss_energy numeric(16, 4),
    add column rate_model_no varchar(8),
    add column rate_snapshot jsonb,
    add column fee_breakdown jsonb,
    add column partial boolean not null default false;
alter table bill drop constraint if exists uq_bill_source;
create unique index uq_bill_source_device
    on bill (device_id, protocol, source_type, source_transaction_no);

-- ---------------------------------------------------------------- 站点与站点范围

drop table if exists pep_platform.user_station_scope;
alter table pep_platform.app_user drop column if exists all_stations;
alter table pep_agent.ai_permission_policy drop column if exists station_scope;

drop table if exists station;
