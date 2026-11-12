-- =============================================================================
-- iot-protocol-expert 运维平台 · 多设备能源运维模型（V1 重建）
--
-- 设计要点：
--   1. 设备 = 产品(物模型模板) + 实例(覆盖)。物模型是一份强类型 JSON，不建元数据表。
--   2. 通道(device_channel)是显式实体：充电枪 / 光伏组串 / 储能簇 / 摄像头镜头同构。
--   3. 实时值按「一属性一行、按类型选列」存 device_property_latest，另留时序表。
--   4. 充电域(charge_order/bill/rate_model)保留专属表：通用性在设备与数据，不在计费。
--   5. JSON 列一律 jsonb，Java 侧映射为 String（JsonbStringType），避开 Hibernate/Jackson 版本耦合。
-- =============================================================================

-- ---------------------------------------------------------------- 基础域

create table station (
    id           bigserial primary key,
    station_code varchar(64)  not null unique,
    name         varchar(128) not null,
    address      varchar(256),
    timezone     varchar(64)  not null default 'Asia/Shanghai',
    longitude    numeric(10, 6),
    latitude     numeric(10, 6),
    status       varchar(16)  not null default 'ENABLED',
    created_at   timestamp,
    updated_at   timestamp
);

create table device_category (
    id            bigserial primary key,
    category_code varchar(64) not null unique,
    name          varchar(64) not null,
    parent_id     bigint references device_category (id),
    icon          varchar(64),
    sort_order    int         not null default 0,
    created_at    timestamp
);

-- ---------------------------------------------------------------- 产品（物模型宿主）

create table product (
    id              bigserial primary key,
    product_code    varchar(64)  not null unique,
    name            varchar(128) not null,
    category_code   varchar(64)  not null,
    device_type     varchar(32)  not null,             -- CHARGER/PV/WIND/STORAGE/LOCK/GATE/CAMERA/METER
    protocol        varchar(32)  not null,             -- YKC16/OCPP16J/MODBUS_TCP/MQTT/RTSP
    transport       varchar(16)  not null default 'NONE', -- TCP/WS/MQTT/HTTP/NONE
    vendor          varchar(64),
    model           varchar(64),
    metadata        jsonb        not null,             -- 物模型
    configuration   jsonb,                             -- 协议默认配置（超时、心跳、帧参数等）
    scene_model     varchar(64),                       -- 3D 场景默认节点名
    description     varchar(512),
    state           varchar(16)  not null default 'PUBLISHED', -- DRAFT/PUBLISHED
    created_at      timestamp,
    updated_at      timestamp
);
create index idx_product_type on product (device_type);

-- ---------------------------------------------------------------- 设备实例

create table device_instance (
    id                 bigserial primary key,
    device_code        varchar(64)  not null unique,   -- 平台侧唯一编码（原 sn）
    name               varchar(128) not null,
    product_id         bigint       not null references product (id),
    device_type        varchar(32)  not null,          -- 冗余自 product，列表免 join
    protocol           varchar(32)  not null,          -- 冗余自 product
    protocol_device_id varchar(64)  not null,          -- 协议侧编号
    station_id         bigint references station (id),
    parent_id          bigint references device_instance (id),
    state              varchar(16)  not null default 'NOT_ACTIVE', -- NOT_ACTIVE/OFFLINE/ONLINE/FAULT
    enabled            boolean      not null default true,
    allow_simulate     boolean      not null default true,
    allow_work         boolean      not null default true,
    derive_metadata    jsonb,                          -- 设备级物模型覆盖（空=继承产品）
    configuration      jsonb,                          -- 设备级配置（覆盖产品同名键）
    scene_anchor       jsonb,                          -- {"model":"PV","position":[-4.4,2.3,-6.3],"scale":1}
    session_id         varchar(64),
    last_seen          timestamp,
    online_at          timestamp,
    registry_time      timestamp,
    remark             varchar(256),
    created_at         timestamp,
    updated_at         timestamp,
    constraint uq_device_protocol_code unique (protocol, protocol_device_id)
);
create index idx_device_station on device_instance (station_id);
create index idx_device_type on device_instance (device_type);
create index idx_device_state on device_instance (state);
create index idx_device_parent on device_instance (parent_id);

-- 标签：横向扩展设备属性而不改表
create table device_tag (
    id         bigserial primary key,
    device_id  bigint      not null references device_instance (id) on delete cascade,
    tag_key    varchar(64) not null,
    tag_value  varchar(256),
    value_type varchar(24) not null default 'string',
    updated_at timestamp,
    constraint uq_device_tag unique (device_id, tag_key)
);
create index idx_device_tag_key on device_tag (tag_key, tag_value);

-- 通道：枪 / 组串 / 电池簇 / 镜头 / 相
create table device_channel (
    id           bigserial primary key,
    device_id    bigint      not null references device_instance (id) on delete cascade,
    channel_no   varchar(16) not null,                 -- '01' '02' / 'MPPT1' / 'PACK1' / 'L1'
    channel_type varchar(32) not null,                 -- CONNECTOR/STRING/PACK/LENS/PHASE
    name         varchar(64),
    enabled      boolean     not null default true,
    metadata     jsonb,
    created_at   timestamp,
    updated_at   timestamp,
    constraint uq_device_channel unique (device_id, channel_no)
);

-- ---------------------------------------------------------------- 数据域

-- 最新值：一个属性一行，按值类型选列（查询走 value_num）
create table device_property_latest (
    id          bigserial primary key,
    device_id   bigint       not null references device_instance (id) on delete cascade,
    channel_no  varchar(16)  not null default '',      -- 无通道属性用空串
    property_id varchar(64)  not null,
    value_type  varchar(24)  not null,
    value_num   numeric(20, 6),
    value_text  varchar(512),
    value_json  jsonb,
    value_time  timestamp,
    updated_at  timestamp,
    constraint uq_property_latest unique (device_id, channel_no, property_id)
);
create index idx_prop_latest_lookup on device_property_latest (device_id, property_id);

-- 时序：先落 PG，规模上来后按 StorageStrategy 换库
create table device_property_ts (
    id           bigserial primary key,
    device_id    bigint       not null,
    channel_no   varchar(16)  not null default '',
    property_id  varchar(64)  not null,
    value_num    numeric(20, 6),
    value_text   varchar(512),
    value_json   jsonb,
    collected_at timestamp    not null,
    created_at   timestamp
);
create index idx_prop_ts_lookup on device_property_ts (device_id, property_id, collected_at desc);

create table device_event_log (
    id          bigserial primary key,
    device_id   bigint      not null references device_instance (id) on delete cascade,
    channel_no  varchar(16),
    event_id    varchar(64) not null,
    level       varchar(16) not null default 'INFO',
    content     jsonb,
    occurred_at timestamp   not null,
    created_at  timestamp
);
create index idx_event_device_time on device_event_log (device_id, occurred_at desc);

-- ---------------------------------------------------------------- 告警域

create table alarm_rule (
    id               bigserial primary key,
    rule_code        varchar(64)  not null unique,
    name             varchar(128) not null,
    product_id       bigint references product (id),
    device_id        bigint references device_instance (id) on delete cascade,
    channel_no       varchar(16),
    level            varchar(16)  not null default 'WARN',   -- INFO/WARN/MAJOR/CRITICAL
    metric_property  varchar(64)  not null,
    operator         varchar(8)   not null,                  -- gt/ge/lt/le/eq/ne
    threshold        numeric(20, 6),
    duration_seconds int          not null default 0,
    enabled          boolean      not null default true,
    description      varchar(512),
    created_at       timestamp,
    updated_at       timestamp
);
create index idx_alarm_rule_scope on alarm_rule (product_id, device_id, enabled);

create table alarm_record (
    id            bigserial primary key,
    rule_id       bigint references alarm_rule (id),
    device_id     bigint      not null references device_instance (id) on delete cascade,
    channel_no    varchar(16),
    level         varchar(16) not null,
    title         varchar(256) not null,
    detail        jsonb,
    state         varchar(16) not null default 'OPEN',   -- OPEN/ACK/CLOSED
    first_at      timestamp   not null,
    last_at       timestamp   not null,
    handled_by    varchar(64),
    handled_at    timestamp,
    handling_note varchar(512),
    created_at    timestamp
);
create index idx_alarm_device on alarm_record (device_id, state, last_at desc);
create index idx_alarm_state on alarm_record (state, last_at desc);

-- ---------------------------------------------------------------- 充电域（CHARGER 专属）

create table charge_order (
    id                      bigserial primary key,
    order_no                varchar(40) not null unique,
    device_id               bigint      not null references device_instance (id),
    gun_no                  varchar(4)  not null,
    station_id              bigint references station (id),
    protocol                varchar(24) not null,
    protocol_transaction_id varchar(64),
    ocpp_transaction_id     int,
    meter_start_wh          numeric(20, 3),
    status                  varchar(16) not null default 'PENDING',
    start_type              int         not null default 0,
    physical_card           varchar(32),
    logical_card            varchar(32),
    start_time              timestamp,
    end_time                timestamp,
    energy                  numeric(16, 4) not null default 0,
    amount                  numeric(16, 4) not null default 0,
    amount_source           varchar(24) not null default 'PLATFORM_ESTIMATE',
    stop_reason             int         not null default 0,
    fail_reason             varchar(256),
    created_at              timestamp,
    updated_at              timestamp
);
create index idx_order_device on charge_order (device_id);
create index idx_order_station_time on charge_order (station_id, end_time);
create index idx_order_status on charge_order (status);
create index idx_order_protocol_tx on charge_order (protocol, protocol_transaction_id);
create index idx_order_ocpp_tx on charge_order (ocpp_transaction_id);

create table bill (
    id                    bigserial primary key,
    order_id              bigint references charge_order (id),
    order_no              varchar(40),
    device_id             bigint      not null references device_instance (id),
    station_id            bigint references station (id),
    protocol              varchar(24) not null,
    source_type           varchar(32) not null,
    source_transaction_no varchar(64) not null,
    energy                numeric(16, 4) not null default 0,
    amount                numeric(16, 4) not null default 0,
    currency              varchar(8)  not null default 'CNY',
    amount_type           varchar(24) not null,
    stop_reason           int         not null default 0,
    ended_at              timestamp,
    received_at           timestamp,
    accepted              boolean     not null default true,
    reject_reason         varchar(256),
    raw_payload           text,
    created_at            timestamp,
    constraint uq_bill_source unique (protocol, source_type, source_transaction_no)
);
create index idx_bill_device_time on bill (device_id, ended_at);
create index idx_bill_station_time on bill (station_id, ended_at);
create index idx_bill_order on bill (order_id);

create table rate_model (
    id            bigserial primary key,
    model_no      varchar(8) not null unique,
    sharp_energy  numeric(12, 5) not null default 0,
    sharp_service numeric(12, 5) not null default 0,
    peak_energy   numeric(12, 5) not null default 0,
    peak_service  numeric(12, 5) not null default 0,
    flat_energy   numeric(12, 5) not null default 0,
    flat_service  numeric(12, 5) not null default 0,
    valley_energy numeric(12, 5) not null default 0,
    valley_service numeric(12, 5) not null default 0,
    loss_ratio    int    not null default 0,
    segments      varchar(128) not null,
    active        boolean not null default false,
    updated_at    timestamp
);

-- ---------------------------------------------------------------- 运维域

create table frame_log (
    id                 bigserial primary key,
    device_id          bigint references device_instance (id) on delete set null,
    protocol           varchar(24) not null,
    protocol_device_id varchar(64),
    direction          varchar(8)  not null,
    frame_type         int         not null default 0,
    frame_label        varchar(64),
    message_id         varchar(64),
    sequence_no        int         not null default 0,
    encrypted          boolean     not null default false,
    body_hex           varchar(8192),
    wire_hex           varchar(8192),
    summary            varchar(512),
    error_message      varchar(512),
    trace_id           varchar(64),
    created_at         timestamp
);
create index idx_frame_device_time on frame_log (device_id, created_at);
create index idx_frame_time on frame_log (created_at);
create index idx_frame_message on frame_log (message_id);
create index idx_frame_trace on frame_log (trace_id);

create table command_record (
    id               bigserial primary key,
    device_id        bigint      not null references device_instance (id) on delete cascade,
    protocol         varchar(24) not null,
    command_type     varchar(64) not null,
    request_payload  text,
    status           varchar(24) not null default 'PENDING',
    sent_at          timestamp,
    ack_at           timestamp,
    response_payload text,
    operator         varchar(64),
    created_at       timestamp,
    updated_at       timestamp
);
create index idx_command_device_time on command_record (device_id, created_at);

create table ops_user (
    id            bigserial primary key,
    username      varchar(64) not null unique,
    password_hash varchar(128) not null,
    role          varchar(24) not null,
    enabled       boolean not null default true,
    created_at    timestamp
);

create table audit_log (
    id          bigserial primary key,
    actor       varchar(64),
    action      varchar(64) not null,
    target_type varchar(64),
    target_id   varchar(64),
    result      varchar(24) not null default 'SUCCESS',
    detail      varchar(1024),
    created_at  timestamp
);
create index idx_audit_time on audit_log (created_at);

create table protocol_doc (
    id         bigserial primary key,
    protocol   varchar(24)  not null,
    version    varchar(32)  not null,
    title      varchar(256) not null,
    source     varchar(512),
    created_at timestamp
);

create table protocol_doc_section (
    id              bigserial primary key,
    doc_id          bigint      not null references protocol_doc (id),
    protocol        varchar(24) not null,
    version         varchar(32) not null,
    frame_or_action varchar(64),
    heading         varchar(256),
    content         text        not null,
    locator         varchar(128),
    keywords        varchar(512)
);
create index idx_doc_section_filter on protocol_doc_section (protocol, version, frame_or_action);

create table trouble_case (
    id          bigserial primary key,
    device_id   bigint references device_instance (id) on delete set null,
    order_no    varchar(40),
    protocol    varchar(24),
    question    varchar(1024),
    result_json text,
    created_by  varchar(64),
    created_at  timestamp
);

-- =============================================================================
-- 种子数据
-- =============================================================================

insert into device_category (category_code, name, sort_order, created_at) values
 ('energy',     '能源设备', 0, now()),
 ('generation', '发电',     1, now()),
 ('storage',    '储能',     2, now()),
 ('charging',   '充电',     3, now()),
 ('security',   '安防',     4, now()),
 ('metering',   '计量',     5, now());

insert into station (station_code, name, address, timezone, longitude, latitude, status, created_at, updated_at)
values ('ST-DEMO', '光储充放示范园区', '光储充放园区 · 演示地址', 'Asia/Shanghai', 120.155070, 30.274085,
        'ENABLED', now(), now());

-- ------------------------------------------------------------------ 产品定义

-- 1. 云快充直流充电桩
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-CHARGER-YKC', '云快充直流充电桩', 'charging', 'CHARGER', 'YKC16', 'TCP', 'Demo', 'YKC-120KW',
        'Charger', '双枪直流快充，云快充 1.6 协议（TCP 8768）', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"设备状态","valueType":{"type":"enum","elements":[{"value":"idle","text":"待机"},{"value":"charging","text":"充电中"},{"value":"fault","text":"故障"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"allowWork","name":"允许工作","valueType":{"type":"bool"}},
    {"id":"maxPowerPercent","name":"功率上限","valueType":{"type":"int","min":0,"max":100,"unit":"%"}},
    {"id":"ratedPower","name":"额定功率","valueType":{"type":"float","min":0,"max":500,"unit":"kW","scale":1}},
    {"id":"activePower","name":"实时功率","valueType":{"type":"float","min":-500,"max":500,"unit":"kW","scale":2},"readOnly":true},
    {"id":"todayEnergy","name":"今日电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":4},"readOnly":true},
    {"id":"totalEnergy","name":"累计电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"todayAmount","name":"今日金额","valueType":{"type":"float","min":0,"unit":"元","scale":2},"readOnly":true},
    {"id":"gunState","name":"枪状态","valueType":{"type":"enum","elements":[{"value":"idle","text":"空闲"},{"value":"preparing","text":"准备中"},{"value":"charging","text":"充电中"},{"value":"suspended","text":"挂起"},{"value":"finishing","text":"结算中"},{"value":"fault","text":"故障"}]},"channel":true,"readOnly":true},
    {"id":"plugged","name":"插枪","valueType":{"type":"bool"},"channel":true,"readOnly":true},
    {"id":"voltage","name":"输出电压","valueType":{"type":"float","min":0,"max":1000,"unit":"V","scale":2},"channel":true,"readOnly":true},
    {"id":"current","name":"输出电流","valueType":{"type":"float","min":0,"max":500,"unit":"A","scale":2},"channel":true,"readOnly":true},
    {"id":"soc","name":"电池 SOC","valueType":{"type":"int","min":0,"max":100,"unit":"%"},"channel":true,"readOnly":true},
    {"id":"batteryTemp","name":"电池最高温度","valueType":{"type":"int","min":-40,"max":150,"unit":"℃"},"channel":true,"readOnly":true},
    {"id":"gunLineTemp","name":"枪线温度","valueType":{"type":"int","min":-40,"max":150,"unit":"℃"},"channel":true,"readOnly":true},
    {"id":"chargeEnergy","name":"本次电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":4},"channel":true,"readOnly":true},
    {"id":"chargeAmount","name":"本次金额","valueType":{"type":"float","min":0,"unit":"元","scale":4},"channel":true,"readOnly":true},
    {"id":"remainMinutes","name":"剩余时间","valueType":{"type":"int","min":0,"unit":"min"},"channel":true,"readOnly":true},
    {"id":"transactionNo","name":"交易流水号","valueType":{"type":"string","maxLength":40},"channel":true,"readOnly":true}
  ],
  "functions": [
    {"id":"startCharge","name":"启动充电","async":true,"inputs":[
      {"id":"gunNo","name":"枪号","valueType":{"type":"string","maxLength":4}},
      {"id":"cardNo","name":"卡号","valueType":{"type":"string","maxLength":16}},
      {"id":"limitEnergy","name":"限电量","valueType":{"type":"float","unit":"kWh"}},
      {"id":"limitAmount","name":"限金额","valueType":{"type":"float","unit":"元"}},
      {"id":"limitMinutes","name":"限时长","valueType":{"type":"int","unit":"min"}}],
      "output":{"type":"object","properties":[{"id":"orderNo","name":"订单号","valueType":{"type":"string"}}]}},
    {"id":"stopCharge","name":"停止充电","async":true,"inputs":[
      {"id":"gunNo","name":"枪号","valueType":{"type":"string","maxLength":4}},
      {"id":"reason","name":"停止原因","valueType":{"type":"int"}}]},
    {"id":"unlockGun","name":"解锁枪","async":true,"inputs":[{"id":"gunNo","name":"枪号","valueType":{"type":"string","maxLength":4}}]},
    {"id":"setRateModel","name":"下发费率","async":true,"inputs":[{"id":"modelNo","name":"费率号","valueType":{"type":"string","maxLength":8}}]},
    {"id":"setPowerLimit","name":"设置功率上限","async":true,"inputs":[{"id":"percent","name":"百分比","valueType":{"type":"int","min":0,"max":100}}]},
    {"id":"reboot","name":"重启设备","async":true,"inputs":[]}
  ],
  "events": [
    {"id":"chargeStarted","name":"充电开始","level":"INFO","valueType":{"type":"object","properties":[{"id":"gunNo","name":"枪号","valueType":{"type":"string"}},{"id":"orderNo","name":"订单号","valueType":{"type":"string"}}]}},
    {"id":"chargeStopped","name":"充电结束","level":"INFO","valueType":{"type":"object","properties":[{"id":"gunNo","name":"枪号","valueType":{"type":"string"}},{"id":"energy","name":"电量","valueType":{"type":"float","unit":"kWh"}},{"id":"reason","name":"原因","valueType":{"type":"string"}}]}},
    {"id":"gunPlugged","name":"插枪","level":"INFO","valueType":{"type":"object","properties":[{"id":"gunNo","name":"枪号","valueType":{"type":"string"}},{"id":"plugged","name":"插枪","valueType":{"type":"bool"}}]}},
    {"id":"fault","name":"设备故障","level":"MAJOR","valueType":{"type":"object","properties":[{"id":"code","name":"故障码","valueType":{"type":"string"}},{"id":"gunNo","name":"枪号","valueType":{"type":"string"}}]}},
    {"id":"billUploaded","name":"账单上传","level":"INFO","valueType":{"type":"object","properties":[{"id":"orderNo","name":"订单号","valueType":{"type":"string"}},{"id":"amount","name":"金额","valueType":{"type":"float","unit":"元"}}]}}
  ],
  "tags": [
    {"id":"pileType","name":"桩类型","valueType":{"type":"int"}},
    {"id":"networkType","name":"网络类型","valueType":{"type":"int"}},
    {"id":"simCard","name":"SIM 卡号","valueType":{"type":"string","maxLength":64}},
    {"id":"operator","name":"运营商","valueType":{"type":"int"}},
    {"id":"gunCount","name":"枪数","valueType":{"type":"int","min":1,"max":8}}
  ]
}$md$::jsonb,
$cfg${"protocolVersion":16,"heartbeatSeconds":60,"authTimeoutMs":5000,"frameEncryption":"none"}$cfg$::jsonb);

-- 2. OCPP 1.6J 交流桩
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-CHARGER-OCPP', 'OCPP 1.6J 交流充电桩', 'charging', 'CHARGER', 'OCPP16J', 'WS', 'Demo', 'OCPP-22KW',
        'Charger', '单枪交流慢充，OCPP 1.6J（WebSocket 8769）', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"设备状态","valueType":{"type":"enum","elements":[{"value":"idle","text":"待机"},{"value":"charging","text":"充电中"},{"value":"fault","text":"故障"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"allowWork","name":"允许工作","valueType":{"type":"bool"}},
    {"id":"maxPowerPercent","name":"功率上限","valueType":{"type":"int","min":0,"max":100,"unit":"%"}},
    {"id":"ratedPower","name":"额定功率","valueType":{"type":"float","min":0,"max":50,"unit":"kW","scale":1}},
    {"id":"activePower","name":"实时功率","valueType":{"type":"float","min":0,"max":50,"unit":"kW","scale":2},"readOnly":true},
    {"id":"todayEnergy","name":"今日电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":4},"readOnly":true},
    {"id":"totalEnergy","name":"累计电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"gunState","name":"枪状态","valueType":{"type":"enum","elements":[{"value":"idle","text":"空闲"},{"value":"preparing","text":"准备中"},{"value":"charging","text":"充电中"},{"value":"suspended","text":"挂起"},{"value":"finishing","text":"结算中"},{"value":"fault","text":"故障"}]},"channel":true,"readOnly":true},
    {"id":"plugged","name":"插枪","valueType":{"type":"bool"},"channel":true,"readOnly":true},
    {"id":"voltage","name":"输出电压","valueType":{"type":"float","min":0,"max":500,"unit":"V","scale":2},"channel":true,"readOnly":true},
    {"id":"current","name":"输出电流","valueType":{"type":"float","min":0,"max":64,"unit":"A","scale":2},"channel":true,"readOnly":true},
    {"id":"soc","name":"电池 SOC","valueType":{"type":"int","min":0,"max":100,"unit":"%"},"channel":true,"readOnly":true},
    {"id":"chargeEnergy","name":"本次电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":4},"channel":true,"readOnly":true},
    {"id":"chargeAmount","name":"本次金额","valueType":{"type":"float","min":0,"unit":"元","scale":4},"channel":true,"readOnly":true},
    {"id":"transactionNo","name":"交易号","valueType":{"type":"string","maxLength":40},"channel":true,"readOnly":true}
  ],
  "functions": [
    {"id":"startCharge","name":"启动充电","async":true,"inputs":[{"id":"gunNo","name":"枪号","valueType":{"type":"string","maxLength":4}},{"id":"cardNo","name":"卡号","valueType":{"type":"string","maxLength":20}}]},
    {"id":"stopCharge","name":"停止充电","async":true,"inputs":[{"id":"gunNo","name":"枪号","valueType":{"type":"string","maxLength":4}}]},
    {"id":"unlockGun","name":"解锁枪","async":true,"inputs":[{"id":"gunNo","name":"枪号","valueType":{"type":"string","maxLength":4}}]},
    {"id":"reboot","name":"重启设备","async":true,"inputs":[]}
  ],
  "events": [
    {"id":"chargeStarted","name":"充电开始","level":"INFO","valueType":{"type":"object","properties":[{"id":"gunNo","name":"枪号","valueType":{"type":"string"}},{"id":"orderNo","name":"订单号","valueType":{"type":"string"}}]}},
    {"id":"chargeStopped","name":"充电结束","level":"INFO","valueType":{"type":"object","properties":[{"id":"gunNo","name":"枪号","valueType":{"type":"string"}},{"id":"energy","name":"电量","valueType":{"type":"float","unit":"kWh"}}]}},
    {"id":"fault","name":"设备故障","level":"MAJOR","valueType":{"type":"object","properties":[{"id":"code","name":"故障码","valueType":{"type":"string"}}]}}
  ],
  "tags": [
    {"id":"chargePointVendor","name":"厂商","valueType":{"type":"string","maxLength":64}},
    {"id":"chargePointModel","name":"型号","valueType":{"type":"string","maxLength":64}},
    {"id":"chargePointSerialNumber","name":"序列号","valueType":{"type":"string","maxLength":64}},
    {"id":"connectorCount","name":"连接器数","valueType":{"type":"int","min":1,"max":8}}
  ]
}$md$::jsonb,
$cfg${"heartbeatSeconds":300,"webSocketPath":"/ocpp/{chargePointId}"}$cfg$::jsonb);

-- 3. 组串式光伏逆变器
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-PV-100K', '组串式光伏逆变器 100kW', 'generation', 'PV', 'MODBUS_TCP', 'TCP', 'Demo', 'SG100CX',
        'PV', '8 路 MPPT，含辐照度与效率监测', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"运行状态","valueType":{"type":"enum","elements":[{"value":"standby","text":"待机"},{"value":"running","text":"运行"},{"value":"derating","text":"限功率"},{"value":"fault","text":"故障"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"irradiance","name":"辐照度","valueType":{"type":"float","min":0,"max":1500,"unit":"W/m²","scale":0},"readOnly":true},
    {"id":"activePower","name":"有功功率","valueType":{"type":"float","min":-200,"max":200,"unit":"kW","scale":2},"readOnly":true},
    {"id":"reactivePower","name":"无功功率","valueType":{"type":"float","min":-100,"max":100,"unit":"kvar","scale":2},"readOnly":true},
    {"id":"powerFactor","name":"功率因数","valueType":{"type":"float","min":-1,"max":1,"scale":3},"readOnly":true},
    {"id":"efficiency","name":"转换效率","valueType":{"type":"float","min":0,"max":100,"unit":"%","scale":2},"readOnly":true},
    {"id":"dcVoltage","name":"直流电压","valueType":{"type":"float","min":0,"max":1500,"unit":"V","scale":1},"readOnly":true},
    {"id":"dcCurrent","name":"直流电流","valueType":{"type":"float","min":0,"max":400,"unit":"A","scale":2},"readOnly":true},
    {"id":"acVoltage","name":"交流电压","valueType":{"type":"float","min":0,"max":500,"unit":"V","scale":1},"readOnly":true},
    {"id":"acCurrent","name":"交流电流","valueType":{"type":"float","min":0,"max":300,"unit":"A","scale":2},"readOnly":true},
    {"id":"cabinetTemp","name":"机内温度","valueType":{"type":"float","min":-40,"max":120,"unit":"℃","scale":1},"readOnly":true},
    {"id":"todayEnergy","name":"今日发电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"totalEnergy","name":"累计发电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":1},"readOnly":true},
    {"id":"runningHours","name":"运行小时数","valueType":{"type":"float","min":0,"unit":"h","scale":1},"readOnly":true},
    {"id":"powerLimitPercent","name":"功率限值","valueType":{"type":"int","min":0,"max":100,"unit":"%"}},
    {"id":"stringVoltage","name":"组串电压","valueType":{"type":"float","min":0,"max":1500,"unit":"V","scale":1},"channel":true,"readOnly":true},
    {"id":"stringCurrent","name":"组串电流","valueType":{"type":"float","min":0,"max":20,"unit":"A","scale":2},"channel":true,"readOnly":true},
    {"id":"stringPower","name":"组串功率","valueType":{"type":"float","min":0,"max":30,"unit":"kW","scale":2},"channel":true,"readOnly":true},
    {"id":"stringEnergy","name":"组串发电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"channel":true,"readOnly":true}
  ],
  "functions": [
    {"id":"setPowerLimit","name":"设置功率限值","async":true,"inputs":[{"id":"percent","name":"百分比","valueType":{"type":"int","min":0,"max":100}}]},
    {"id":"start","name":"启动","async":true,"inputs":[]},
    {"id":"stop","name":"停机","async":true,"inputs":[]},
    {"id":"reset","name":"故障复位","async":true,"inputs":[]}
  ],
  "events": [
    {"id":"overTemp","name":"过温降额","level":"WARN","valueType":{"type":"object","properties":[{"id":"temperature","name":"温度","valueType":{"type":"float","unit":"℃"}}]}},
    {"id":"stringFault","name":"组串故障","level":"MAJOR","valueType":{"type":"object","properties":[{"id":"stringNo","name":"组串号","valueType":{"type":"string"}},{"id":"code","name":"故障码","valueType":{"type":"string"}}]}},
    {"id":"gridLoss","name":"电网失电","level":"MAJOR","valueType":{"type":"object","properties":[]}},
    {"id":"derating","name":"限功率运行","level":"INFO","valueType":{"type":"object","properties":[{"id":"percent","name":"限值","valueType":{"type":"int","unit":"%"}}]}}
  ],
  "tags": [
    {"id":"ratedPowerKw","name":"额定功率","valueType":{"type":"int","unit":"kW"}},
    {"id":"mpptCount","name":"MPPT 路数","valueType":{"type":"int","min":1,"max":32}}
  ]
}$md$::jsonb,
$cfg${"modbusUnitId":1,"pollIntervalMs":5000,"registerMap":"sg100cx"}$cfg$::jsonb);

-- 4. 风力发电机
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-WIND-2M', '双馈风力发电机 2MW', 'generation', 'WIND', 'MODBUS_TCP', 'TCP', 'Demo', 'DF-2000',
        'Wind', '2MW 双馈机组，含偏航与振动监测', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"运行状态","valueType":{"type":"enum","elements":[{"value":"idle","text":"待机"},{"value":"starting","text":"启动中"},{"value":"running","text":"并网发电"},{"value":"stopping","text":"停机中"},{"value":"fault","text":"故障"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"windSpeed","name":"风速","valueType":{"type":"float","min":0,"max":60,"unit":"m/s","scale":1},"readOnly":true},
    {"id":"windDirection","name":"风向","valueType":{"type":"float","min":0,"max":360,"unit":"°","scale":0},"readOnly":true},
    {"id":"rotorSpeed","name":"转速","valueType":{"type":"float","min":0,"max":30,"unit":"rpm","scale":1},"readOnly":true},
    {"id":"activePower","name":"有功功率","valueType":{"type":"float","min":-200,"max":2500,"unit":"kW","scale":1},"readOnly":true},
    {"id":"reactivePower","name":"无功功率","valueType":{"type":"float","min":-1000,"max":1000,"unit":"kvar","scale":1},"readOnly":true},
    {"id":"gridFrequency","name":"并网频率","valueType":{"type":"float","min":45,"max":55,"unit":"Hz","scale":2},"readOnly":true},
    {"id":"nacelleTemp","name":"机舱温度","valueType":{"type":"float","min":-40,"max":120,"unit":"℃","scale":1},"readOnly":true},
    {"id":"yawAngle","name":"偏航角","valueType":{"type":"float","min":-180,"max":180,"unit":"°","scale":0},"readOnly":true},
    {"id":"vibration","name":"振动幅值","valueType":{"type":"float","min":0,"max":50,"unit":"mm/s","scale":2},"readOnly":true},
    {"id":"todayEnergy","name":"今日发电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"totalEnergy","name":"累计发电量","valueType":{"type":"float","min":0,"unit":"MWh","scale":1},"readOnly":true},
    {"id":"runningHours","name":"运行小时数","valueType":{"type":"float","min":0,"unit":"h","scale":1},"readOnly":true},
    {"id":"powerLimitPercent","name":"功率限值","valueType":{"type":"int","min":0,"max":100,"unit":"%"}}
  ],
  "functions": [
    {"id":"start","name":"启动","async":true,"inputs":[]},
    {"id":"stop","name":"停机","async":true,"inputs":[]},
    {"id":"reset","name":"复位","async":true,"inputs":[]},
    {"id":"setYaw","name":"偏航定位","async":true,"inputs":[{"id":"angle","name":"目标角度","valueType":{"type":"float","min":-180,"max":180,"unit":"°"}}]},
    {"id":"setPowerLimit","name":"设置功率限值","async":true,"inputs":[{"id":"percent","name":"百分比","valueType":{"type":"int","min":0,"max":100}}]}
  ],
  "events": [
    {"id":"cutIn","name":"切入发电","level":"INFO","valueType":{"type":"object","properties":[{"id":"windSpeed","name":"风速","valueType":{"type":"float","unit":"m/s"}}]}},
    {"id":"cutOut","name":"切出停机","level":"WARN","valueType":{"type":"object","properties":[{"id":"windSpeed","name":"风速","valueType":{"type":"float","unit":"m/s"}}]}},
    {"id":"vibrationAlarm","name":"振动超限","level":"MAJOR","valueType":{"type":"object","properties":[{"id":"vibration","name":"振动","valueType":{"type":"float","unit":"mm/s"}}]}},
    {"id":"brakeFault","name":"刹车系统故障","level":"CRITICAL","valueType":{"type":"object","properties":[]}},
    {"id":"yawFault","name":"偏航故障","level":"MAJOR","valueType":{"type":"object","properties":[]}}
  ],
  "tags": [
    {"id":"ratedPowerKw","name":"额定功率","valueType":{"type":"int","unit":"kW"}},
    {"id":"hubHeight","name":"轮毂高度","valueType":{"type":"float","unit":"m"}},
    {"id":"rotorDiameter","name":"风轮直径","valueType":{"type":"float","unit":"m"}}
  ]
}$md$::jsonb,
$cfg${"modbusUnitId":1,"pollIntervalMs":3000}$cfg$::jsonb);

-- 5. 液冷储能柜
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-STORAGE-215', '液冷储能柜 215kWh', 'storage', 'STORAGE', 'MODBUS_TCP', 'TCP', 'Demo', 'ESS-215L',
        'Storage', '1P52S 液冷柜，支持并离网切换', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"运行状态","valueType":{"type":"enum","elements":[{"value":"idle","text":"待机"},{"value":"charging","text":"充电"},{"value":"discharging","text":"放电"},{"value":"fault","text":"故障"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"soc","name":"SOC","valueType":{"type":"float","min":0,"max":100,"unit":"%","scale":1},"readOnly":true},
    {"id":"soh","name":"SOH","valueType":{"type":"float","min":0,"max":100,"unit":"%","scale":1},"readOnly":true},
    {"id":"activePower","name":"有功功率","valueType":{"type":"float","min":-250,"max":250,"unit":"kW","scale":2},"readOnly":true,"description":"正为放电，负为充电"},
    {"id":"dcVoltage","name":"直流母线电压","valueType":{"type":"float","min":0,"max":1000,"unit":"V","scale":1},"readOnly":true},
    {"id":"dcCurrent","name":"直流母线电流","valueType":{"type":"float","min":-400,"max":400,"unit":"A","scale":2},"readOnly":true},
    {"id":"availableEnergy","name":"可充电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"ratedEnergy","name":"额定容量","valueType":{"type":"float","min":0,"unit":"kWh","scale":1}},
    {"id":"cellMaxTemp","name":"最高单体温度","valueType":{"type":"float","min":-40,"max":120,"unit":"℃","scale":1},"readOnly":true},
    {"id":"cellMinTemp","name":"最低单体温度","valueType":{"type":"float","min":-40,"max":120,"unit":"℃","scale":1},"readOnly":true},
    {"id":"cellVoltageDiff","name":"单体压差","valueType":{"type":"float","min":0,"max":2000,"unit":"mV","scale":1},"readOnly":true},
    {"id":"cycleCount","name":"循环次数","valueType":{"type":"int","min":0},"readOnly":true},
    {"id":"dailyChargeEnergy","name":"今日充电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"dailyDischargeEnergy","name":"今日放电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"protectionStatus","name":"保护状态","valueType":{"type":"enum","elements":[{"value":"none","text":"正常"},{"value":"overTemp","text":"过温"},{"value":"overCurrent","text":"过流"},{"value":"overVoltage","text":"过压"},{"value":"underVoltage","text":"欠压"}]},"readOnly":true},
    {"id":"targetPowerKw","name":"目标功率","valueType":{"type":"float","min":-250,"max":250,"unit":"kW","scale":2}},
    {"id":"packSoc","name":"簇 SOC","valueType":{"type":"float","min":0,"max":100,"unit":"%","scale":1},"channel":true,"readOnly":true},
    {"id":"packVoltage","name":"簇电压","valueType":{"type":"float","min":0,"max":1000,"unit":"V","scale":1},"channel":true,"readOnly":true},
    {"id":"packCurrent","name":"簇电流","valueType":{"type":"float","min":-200,"max":200,"unit":"A","scale":2},"channel":true,"readOnly":true},
    {"id":"packTemp","name":"簇温度","valueType":{"type":"float","min":-40,"max":120,"unit":"℃","scale":1},"channel":true,"readOnly":true},
    {"id":"packVoltageDiff","name":"簇内压差","valueType":{"type":"float","min":0,"max":2000,"unit":"mV","scale":1},"channel":true,"readOnly":true}
  ],
  "functions": [
    {"id":"setPowerTarget","name":"功率设定","async":true,"inputs":[{"id":"kilowatts","name":"目标功率","valueType":{"type":"float","min":-250,"max":250,"unit":"kW"}}]},
    {"id":"setSocTarget","name":"SOC 设定","async":true,"inputs":[{"id":"soc","name":"目标 SOC","valueType":{"type":"int","min":0,"max":100,"unit":"%"}}]},
    {"id":"start","name":"启动","async":true,"inputs":[]},
    {"id":"stop","name":"停止","async":true,"inputs":[]},
    {"id":"reset","name":"故障复位","async":true,"inputs":[]}
  ],
  "events": [
    {"id":"overTemp","name":"电池过温","level":"CRITICAL","valueType":{"type":"object","properties":[{"id":"temperature","name":"温度","valueType":{"type":"float","unit":"℃"}},{"id":"packNo","name":"簇号","valueType":{"type":"string"}}]}},
    {"id":"cellImbalance","name":"一致性差","level":"WARN","valueType":{"type":"object","properties":[{"id":"voltageDiff","name":"压差","valueType":{"type":"float","unit":"mV"}}]}},
    {"id":"protection","name":"保护动作","level":"MAJOR","valueType":{"type":"object","properties":[{"id":"type","name":"保护类型","valueType":{"type":"string"}}]}},
    {"id":"commLoss","name":"通信中断","level":"MAJOR","valueType":{"type":"object","properties":[]}},
    {"id":"socLow","name":"SOC 偏低","level":"WARN","valueType":{"type":"object","properties":[{"id":"soc","name":"SOC","valueType":{"type":"float","unit":"%"}}]}}
  ],
  "tags": [
    {"id":"cellType","name":"电芯型号","valueType":{"type":"string","maxLength":64}},
    {"id":"packCount","name":"簇数","valueType":{"type":"int","min":1,"max":64}}
  ]
}$md$::jsonb,
$cfg${"modbusUnitId":2,"pollIntervalMs":2000}$cfg$::jsonb);

-- 6. 智能地锁
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-LOCK-SMART', '智能车位地锁', 'security', 'LOCK', 'MQTT', 'MQTT', 'Demo', 'PL-200',
        'Lock', '车位授权后自动降锁，带地磁检测', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"锁状态","valueType":{"type":"enum","elements":[{"value":"raised","text":"升起"},{"value":"lowered","text":"降下"},{"value":"moving","text":"动作中"},{"value":"fault","text":"故障"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"batteryPercent","name":"电池电量","valueType":{"type":"int","min":0,"max":100,"unit":"%"},"readOnly":true},
    {"id":"signalStrength","name":"信号强度","valueType":{"type":"int","min":-120,"max":0,"unit":"dBm"},"readOnly":true},
    {"id":"occupied","name":"车位占用","valueType":{"type":"bool"},"readOnly":true},
    {"id":"lastPlateNo","name":"最近车牌","valueType":{"type":"string","maxLength":16},"readOnly":true},
    {"id":"lastActionAt","name":"最近动作时间","valueType":{"type":"dateTime"},"readOnly":true}
  ],
  "functions": [
    {"id":"lower","name":"降锁","async":true,"inputs":[{"id":"plateNo","name":"车牌号","valueType":{"type":"string","maxLength":16}}]},
    {"id":"raise","name":"升锁","async":true,"inputs":[]},
    {"id":"lock","name":"常锁","async":true,"inputs":[]},
    {"id":"unlock","name":"常开","async":true,"inputs":[]}
  ],
  "events": [
    {"id":"lowered","name":"已降锁","level":"INFO","valueType":{"type":"object","properties":[{"id":"plateNo","name":"车牌号","valueType":{"type":"string"}}]}},
    {"id":"raised","name":"已升锁","level":"INFO","valueType":{"type":"object","properties":[]}},
    {"id":"jammed","name":"机械卡滞","level":"MAJOR","valueType":{"type":"object","properties":[]}},
    {"id":"lowBattery","name":"电量低","level":"WARN","valueType":{"type":"object","properties":[{"id":"percent","name":"电量","valueType":{"type":"int","unit":"%"}}]}}
  ],
  "tags": [
    {"id":"bayNo","name":"车位号","valueType":{"type":"string","maxLength":16}},
    {"id":"gatewaySn","name":"网关编号","valueType":{"type":"string","maxLength":64}}
  ]
}$md$::jsonb,
$cfg${"topicPrefix":"lock/","qos":1}$cfg$::jsonb);

-- 7. 车牌识别道闸
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-GATE-BARRIER', '车牌识别道闸', 'security', 'GATE', 'MQTT', 'MQTT', 'Demo', 'BR-300',
        'GateIn', '入口/出口道闸，含车牌识别与防砸', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"闸状态","valueType":{"type":"enum","elements":[{"value":"closed","text":"关闭"},{"value":"open","text":"开启"},{"value":"opening","text":"开启中"},{"value":"closing","text":"关闭中"},{"value":"fault","text":"故障"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"mode","name":"运行模式","valueType":{"type":"enum","elements":[{"value":"normal","text":"正常"},{"value":"alwaysOpen","text":"常开"},{"value":"alwaysClosed","text":"常闭"}]}},
    {"id":"lastPlateNo","name":"最近车牌","valueType":{"type":"string","maxLength":16},"readOnly":true},
    {"id":"lastPassAt","name":"最近通行时间","valueType":{"type":"dateTime"},"readOnly":true},
    {"id":"passCountToday","name":"今日通行数","valueType":{"type":"int","min":0},"readOnly":true},
    {"id":"antiSmashEnabled","name":"防砸启用","valueType":{"type":"bool"}},
    {"id":"signalStrength","name":"信号强度","valueType":{"type":"int","min":-120,"max":0,"unit":"dBm"},"readOnly":true}
  ],
  "functions": [
    {"id":"open","name":"开闸","async":true,"inputs":[{"id":"reason","name":"原因","valueType":{"type":"string","maxLength":64}}]},
    {"id":"close","name":"关闸","async":true,"inputs":[]},
    {"id":"setMode","name":"设置模式","async":true,"inputs":[{"id":"mode","name":"模式","valueType":{"type":"enum","elements":[{"value":"normal","text":"正常"},{"value":"alwaysOpen","text":"常开"},{"value":"alwaysClosed","text":"常闭"}]}}]}
  ],
  "events": [
    {"id":"vehiclePassed","name":"车辆通行","level":"INFO","valueType":{"type":"object","properties":[{"id":"plateNo","name":"车牌号","valueType":{"type":"string"}},{"id":"direction","name":"方向","valueType":{"type":"string"}}]}},
    {"id":"plateRecognized","name":"车牌识别","level":"INFO","valueType":{"type":"object","properties":[{"id":"plateNo","name":"车牌号","valueType":{"type":"string"}},{"id":"confidence","name":"置信度","valueType":{"type":"float"}}]}},
    {"id":"illegalIntrusion","name":"非法闯入","level":"CRITICAL","valueType":{"type":"object","properties":[]}},
    {"id":"manualOpen","name":"人工开闸","level":"WARN","valueType":{"type":"object","properties":[{"id":"operator","name":"操作人","valueType":{"type":"string"}}]}}
  ],
  "tags": [
    {"id":"gatewaySn","name":"网关编号","valueType":{"type":"string","maxLength":64}},
    {"id":"direction","name":"出入口","valueType":{"type":"enum","elements":[{"value":"in","text":"入口"},{"value":"out","text":"出口"}]}}
  ]
}$md$::jsonb,
$cfg${"topicPrefix":"gate/","qos":1}$cfg$::jsonb);

-- 8. 网络摄像机
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-CAMERA-BULLET', '车牌识别网络摄像机', 'security', 'CAMERA', 'ONVIF', 'HTTP', 'Demo', 'IPC-4MP',
        'CameraIn', '400 万像素枪机，支持抓拍与云台', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"设备状态","valueType":{"type":"enum","elements":[{"value":"online","text":"在线"},{"value":"offline","text":"离线"},{"value":"fault","text":"故障"}]},"readOnly":true},
    {"id":"streamUrl","name":"主码流地址","valueType":{"type":"string","maxLength":256},"readOnly":true},
    {"id":"resolution","name":"分辨率","valueType":{"type":"string","maxLength":24},"readOnly":true},
    {"id":"fps","name":"帧率","valueType":{"type":"int","min":0,"max":60},"readOnly":true},
    {"id":"bitrateKbps","name":"码率","valueType":{"type":"int","min":0,"unit":"kbps"},"readOnly":true},
    {"id":"recordEnabled","name":"录像启用","valueType":{"type":"bool"}},
    {"id":"storageUsedPercent","name":"存储占用","valueType":{"type":"float","min":0,"max":100,"unit":"%","scale":1},"readOnly":true},
    {"id":"lastCaptureAt","name":"最近抓拍","valueType":{"type":"dateTime"},"readOnly":true},
    {"id":"lastPlateNo","name":"最近车牌","valueType":{"type":"string","maxLength":16},"readOnly":true},
    {"id":"ptzPan","name":"云台水平","valueType":{"type":"float","min":-180,"max":180,"unit":"°","scale":0},"readOnly":true},
    {"id":"ptzTilt","name":"云台俯仰","valueType":{"type":"float","min":-90,"max":90,"unit":"°","scale":0},"readOnly":true}
  ],
  "functions": [
    {"id":"snapshot","name":"抓拍","async":true,"inputs":[]},
    {"id":"startRecord","name":"开始录像","async":true,"inputs":[]},
    {"id":"stopRecord","name":"停止录像","async":true,"inputs":[]},
    {"id":"ptzControl","name":"云台控制","async":true,"inputs":[{"id":"direction","name":"方向","valueType":{"type":"enum","elements":[{"value":"left","text":"左"},{"value":"right","text":"右"},{"value":"up","text":"上"},{"value":"down","text":"下"}]}},{"id":"speed","name":"速度","valueType":{"type":"int","min":1,"max":10}}]}
  ],
  "events": [
    {"id":"plateRecognized","name":"车牌识别","level":"INFO","valueType":{"type":"object","properties":[{"id":"plateNo","name":"车牌号","valueType":{"type":"string"}},{"id":"confidence","name":"置信度","valueType":{"type":"float"}},{"id":"imageUrl","name":"抓拍图","valueType":{"type":"string"}}]}},
    {"id":"motionDetected","name":"移动侦测","level":"INFO","valueType":{"type":"object","properties":[]}},
    {"id":"storageFull","name":"存储将满","level":"WARN","valueType":{"type":"object","properties":[{"id":"percent","name":"占用","valueType":{"type":"float","unit":"%"}}]}},
    {"id":"offline","name":"离线","level":"MAJOR","valueType":{"type":"object","properties":[]}}
  ],
  "tags": [
    {"id":"ipAddress","name":"IP 地址","valueType":{"type":"string","maxLength":64}},
    {"id":"installPosition","name":"安装位置","valueType":{"type":"string","maxLength":64}}
  ]
}$md$::jsonb,
$cfg${"onvifPort":80,"snapshotPath":"/snapshot.jpg"}$cfg$::jsonb);

-- 9. 三相智能电表
insert into product (product_code, name, category_code, device_type, protocol, transport, vendor, model,
                     scene_model, description, state, created_at, updated_at, metadata, configuration)
values ('PROD-METER-3P', '三相智能电表', 'metering', 'METER', 'MODBUS_TCP', 'TCP', 'Demo', 'DTSD1352',
        null, '园区关口计量表，正反向电量与三相参数', 'PUBLISHED', now(), now(),
$md${
  "properties": [
    {"id":"state","name":"运行状态","valueType":{"type":"enum","elements":[{"value":"online","text":"在线"},{"value":"offline","text":"离线"}]},"readOnly":true},
    {"id":"activePower","name":"总有功功率","valueType":{"type":"float","min":-1000,"max":1000,"unit":"kW","scale":2},"readOnly":true},
    {"id":"reactivePower","name":"总无功功率","valueType":{"type":"float","min":-1000,"max":1000,"unit":"kvar","scale":2},"readOnly":true},
    {"id":"powerFactor","name":"总功率因数","valueType":{"type":"float","min":-1,"max":1,"scale":3},"readOnly":true},
    {"id":"frequency","name":"频率","valueType":{"type":"float","min":45,"max":55,"unit":"Hz","scale":2},"readOnly":true},
    {"id":"forwardEnergy","name":"正向有功电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"reverseEnergy","name":"反向有功电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"todayEnergy","name":"今日电量","valueType":{"type":"float","min":0,"unit":"kWh","scale":2},"readOnly":true},
    {"id":"voltage","name":"相电压","valueType":{"type":"float","min":0,"max":400,"unit":"V","scale":1},"channel":true,"readOnly":true},
    {"id":"current","name":"相电流","valueType":{"type":"float","min":0,"max":200,"unit":"A","scale":2},"channel":true,"readOnly":true},
    {"id":"phasePower","name":"相有功功率","valueType":{"type":"float","min":-400,"max":400,"unit":"kW","scale":2},"channel":true,"readOnly":true}
  ],
  "functions": [
    {"id":"readNow","name":"立即抄表","async":true,"inputs":[]}
  ],
  "events": [
    {"id":"phaseLoss","name":"失压","level":"MAJOR","valueType":{"type":"object","properties":[{"id":"phase","name":"相别","valueType":{"type":"string"}}]}},
    {"id":"overVoltage","name":"过压","level":"WARN","valueType":{"type":"object","properties":[{"id":"voltage","name":"电压","valueType":{"type":"float","unit":"V"}}]}},
    {"id":"underVoltage","name":"欠压","level":"WARN","valueType":{"type":"object","properties":[{"id":"voltage","name":"电压","valueType":{"type":"float","unit":"V"}}]}},
    {"id":"reverseFlow","name":"反向潮流","level":"INFO","valueType":{"type":"object","properties":[]}}
  ],
  "tags": [
    {"id":"meterNo","name":"表号","valueType":{"type":"string","maxLength":32}},
    {"id":"ctRatio","name":"互感器变比","valueType":{"type":"string","maxLength":32}}
  ]
}$md$::jsonb,
$cfg${"modbusUnitId":5,"pollIntervalMs":15000}$cfg$::jsonb);

-- ------------------------------------------------------------------ 设备实例

-- 3D 沙盘设备：锚点与 energy-campus.glb 节点名对齐
insert into device_instance (device_code, name, product_id, device_type, protocol, protocol_device_id,
                             station_id, state, enabled, allow_simulate, allow_work, scene_anchor,
                             registry_time, created_at, updated_at)
values
 ('DEV-PV-001', '1# 光伏阵列',
  (select id from product where product_code = 'PROD-PV-100K'), 'PV', 'MODBUS_TCP', 'PV-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"PV","position":[-4.4,2.3,-6.3],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-WIND-001', '1# 风力发电机',
  (select id from product where product_code = 'PROD-WIND-2M'), 'WIND', 'MODBUS_TCP', 'WIND-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"Wind","position":[-8.8,7.7,-6.3],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-STOR-001', '1# 液冷储能柜',
  (select id from product where product_code = 'PROD-STORAGE-215'), 'STORAGE', 'MODBUS_TCP', 'ESS-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"Storage","position":[1.35,3.2,-6.3],"scale":1}$a$::jsonb, now(), now(), now()),

 ('SN-DEMO-YKC-001', '2# 直流快充桩（云快充）',
  (select id from product where product_code = 'PROD-CHARGER-YKC'), 'CHARGER', 'YKC16', '32010600000001',
  (select id from station where station_code = 'ST-DEMO'), 'OFFLINE', true, true, true,
  $a${"model":"Charger","position":[-3.03,2.2,5.6],"scale":1}$a$::jsonb, now(), now(), now()),

 ('SN-DEMO-OCPP-001', '3# 交流慢充桩（OCPP）',
  (select id from product where product_code = 'PROD-CHARGER-OCPP'), 'CHARGER', 'OCPP16J', '32010600000001',
  (select id from station where station_code = 'ST-DEMO'), 'OFFLINE', true, true, true,
  $a${"model":"Charger","position":[2.4,2.2,5.6],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-LOCK-001', '02 号车位地锁',
  (select id from product where product_code = 'PROD-LOCK-SMART'), 'LOCK', 'MQTT', 'LOCK-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"Lock","position":[-1.43,0.95,-0.02],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-GATE-IN', '园区入口道闸',
  (select id from product where product_code = 'PROD-GATE-BARRIER'), 'GATE', 'MQTT', 'GATE-IN-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"GateIn","position":[-11.0,2.5,-5.05],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-GATE-OUT', '园区出口道闸',
  (select id from product where product_code = 'PROD-GATE-BARRIER'), 'GATE', 'MQTT', 'GATE-OUT-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"GateOut","position":[11.0,2.5,10.25],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-CAM-IN', '入口车牌识别摄像机',
  (select id from product where product_code = 'PROD-CAMERA-BULLET'), 'CAMERA', 'ONVIF', 'CAM-IN-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"CameraIn","position":[-10.2,3.4,-4.6],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-CAM-OUT', '出口车牌识别摄像机',
  (select id from product where product_code = 'PROD-CAMERA-BULLET'), 'CAMERA', 'ONVIF', 'CAM-OUT-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  $a${"model":"CameraOut","position":[10.2,3.4,9.6],"scale":1}$a$::jsonb, now(), now(), now()),

 ('DEV-METER-001', '园区关口电表',
  (select id from product where product_code = 'PROD-METER-3P'), 'METER', 'MODBUS_TCP', 'METER-001',
  (select id from station where station_code = 'ST-DEMO'), 'ONLINE', true, true, true,
  null, now(), now(), now());

-- 通道：充电桩连接器 / 光伏组串 / 储能簇 / 相机镜头 / 电表相
insert into device_channel (device_id, channel_no, channel_type, name, created_at, updated_at)
select d.id, g.gun_no, 'CONNECTOR', '充电枪 ' || g.gun_no, now(), now()
from device_instance d
cross join (values ('01'), ('02')) as g(gun_no)
where d.device_type = 'CHARGER';

insert into device_channel (device_id, channel_no, channel_type, name, created_at, updated_at)
select d.id, 'MPPT' || i, 'STRING', 'MPPT ' || i || ' 组串', now(), now()
from device_instance d cross join generate_series(1, 8) as i
where d.device_type = 'PV';

insert into device_channel (device_id, channel_no, channel_type, name, created_at, updated_at)
select d.id, 'PACK' || i, 'PACK', '电池簇 ' || i, now(), now()
from device_instance d cross join generate_series(1, 4) as i
where d.device_type = 'STORAGE';

insert into device_channel (device_id, channel_no, channel_type, name, created_at, updated_at)
select d.id, 'LENS1', 'LENS', '主镜头', now(), now()
from device_instance d where d.device_type = 'CAMERA';

insert into device_channel (device_id, channel_no, channel_type, name, created_at, updated_at)
select d.id, p, 'PHASE', '相 ' || p, now(), now()
from device_instance d cross join (values ('L1'), ('L2'), ('L3')) as ph(p)
where d.device_type = 'METER';

-- 标签：协议专属字段从主表搬到标签，主表不再随协议变更
insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('pileType', '1', 'int'),
  ('networkType', '1', 'int'),
  ('simCard', '89860000000000000001', 'string'),
  ('operator', '1', 'int'),
  ('gunCount', '2', 'int'),
  ('ratedPowerKw', '120', 'int')
) as t(k, v, t)
where d.device_code = 'SN-DEMO-YKC-001';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('chargePointVendor', 'Demo', 'string'),
  ('chargePointModel', 'OCPP-22KW', 'string'),
  ('chargePointSerialNumber', 'SN-DEMO-OCPP-001', 'string'),
  ('connectorCount', '1', 'int'),
  ('ratedPowerKw', '22', 'int')
) as t(k, v, t)
where d.device_code = 'SN-DEMO-OCPP-001';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('ratedPowerKw', '100', 'int'),
  ('mpptCount', '8', 'int'),
  ('vendor', 'Demo', 'string')
) as t(k, v, t)
where d.device_code = 'DEV-PV-001';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('ratedPowerKw', '2000', 'int'),
  ('hubHeight', '90', 'float'),
  ('rotorDiameter', '116', 'float')
) as t(k, v, t)
where d.device_code = 'DEV-WIND-001';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('cellType', 'LFP-280Ah', 'string'),
  ('packCount', '4', 'int'),
  ('ratedEnergy', '215', 'float')
) as t(k, v, t)
where d.device_code = 'DEV-STOR-001';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('bayNo', 'B02', 'string'),
  ('gatewaySn', 'GW-LOCK-01', 'string')
) as t(k, v, t)
where d.device_code = 'DEV-LOCK-001';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values ('direction', 'in', 'string'), ('gatewaySn', 'GW-GATE-01', 'string')) as t(k, v, t)
where d.device_code = 'DEV-GATE-IN';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values ('direction', 'out', 'string'), ('gatewaySn', 'GW-GATE-01', 'string')) as t(k, v, t)
where d.device_code = 'DEV-GATE-OUT';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('ipAddress', '100.101.91.38', 'string'),
  ('installPosition', '入口', 'string')
) as t(k, v, t)
where d.device_code = 'DEV-CAM-IN';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values
  ('ipAddress', '100.101.91.38', 'string'),
  ('installPosition', '出口', 'string')
) as t(k, v, t)
where d.device_code = 'DEV-CAM-OUT';

insert into device_tag (device_id, tag_key, tag_value, value_type, updated_at)
select d.id, t.k, t.v, t.t, now()
from device_instance d
cross join (values ('meterNo', 'M2026001', 'string'), ('ctRatio', '100/5', 'string')) as t(k, v, t)
where d.device_code = 'DEV-METER-001';

-- ------------------------------------------------------------------ 演示实时值

-- 光伏
insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, '', v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
cross join (values
  ('state', 'string', null::numeric, 'running'),
  ('irradiance', 'float', 642, null),
  ('activePower', 'float', 68.40, null),
  ('reactivePower', 'float', -2.10, null),
  ('powerFactor', 'float', 0.998, null),
  ('efficiency', 'float', 97.60, null),
  ('dcVoltage', 'float', 682.5, null),
  ('dcCurrent', 'float', 103.2, null),
  ('acVoltage', 'float', 231.4, null),
  ('acCurrent', 'float', 168.9, null),
  ('cabinetTemp', 'float', 41.2, null),
  ('todayEnergy', 'float', 412.36, null),
  ('totalEnergy', 'float', 184203.7, null),
  ('runningHours', 'float', 12480.5, null),
  ('powerLimitPercent', 'int', 100, null)
) as v(pid, t, n, s)
where d.device_code = 'DEV-PV-001';

-- 风机
insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, '', v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
cross join (values
  ('state', 'string', null::numeric, 'running'),
  ('windSpeed', 'float', 8.6, null),
  ('windDirection', 'float', 212, null),
  ('rotorSpeed', 'float', 14.2, null),
  ('activePower', 'float', 1180.0, null),
  ('gridFrequency', 'float', 50.01, null),
  ('nacelleTemp', 'float', 36.8, null),
  ('yawAngle', 'float', -12, null),
  ('vibration', 'float', 2.34, null),
  ('todayEnergy', 'float', 8620.4, null),
  ('totalEnergy', 'float', 12840.2, null),
  ('runningHours', 'float', 32104.0, null),
  ('powerLimitPercent', 'int', 100, null)
) as v(pid, t, n, s)
where d.device_code = 'DEV-WIND-001';

-- 储能
insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, '', v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
cross join (values
  ('state', 'string', null::numeric, 'discharging'),
  ('soc', 'float', 63.5, null),
  ('soh', 'float', 97.2, null),
  ('activePower', 'float', 92.4, null),
  ('dcVoltage', 'float', 729.6, null),
  ('dcCurrent', 'float', -126.7, null),
  ('availableEnergy', 'float', 96.8, null),
  ('ratedEnergy', 'float', 215, null),
  ('cellMaxTemp', 'float', 31.4, null),
  ('cellMinTemp', 'float', 28.9, null),
  ('cellVoltageDiff', 'float', 24.5, null),
  ('cycleCount', 'int', 386, null),
  ('dailyChargeEnergy', 'float', 148.2, null),
  ('dailyDischargeEnergy', 'float', 171.6, null),
  ('protectionStatus', 'string', null, 'none'),
  ('targetPowerKw', 'float', 100, null)
) as v(pid, t, n, s)
where d.device_code = 'DEV-STOR-001';

-- 地锁 / 道闸 / 相机 / 电表
insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, '', v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
cross join (values
  ('state', 'string', null::numeric, 'raised'),
  ('batteryPercent', 'int', 86, null),
  ('signalStrength', 'int', -67, null),
  ('occupied', 'bool', 0, null)
) as v(pid, t, n, s)
where d.device_code = 'DEV-LOCK-001';

insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, '', v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
cross join (values
  ('state', 'string', null::numeric, 'closed'),
  ('mode', 'string', null, 'normal'),
  ('passCountToday', 'int', 0, null),
  ('antiSmashEnabled', 'bool', 1, null),
  ('signalStrength', 'int', -58, null)
) as v(pid, t, n, s)
where d.device_type = 'GATE';

insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, '', v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
cross join (values
  ('state', 'string', null::numeric, 'online'),
  ('resolution', 'string', null, '2560x1440'),
  ('fps', 'int', 25, null),
  ('bitrateKbps', 'int', 4096, null),
  ('recordEnabled', 'bool', 1, null),
  ('storageUsedPercent', 'float', 43.5, null)
) as v(pid, t, n, s)
where d.device_type = 'CAMERA';

insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, '', v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
cross join (values
  ('state', 'string', null::numeric, 'online'),
  ('activePower', 'float', 161.2, null),
  ('reactivePower', 'float', 12.4, null),
  ('powerFactor', 'float', 0.997, null),
  ('frequency', 'float', 50.00, null),
  ('forwardEnergy', 'float', 268431.7, null),
  ('reverseEnergy', 'float', 1204.9, null),
  ('todayEnergy', 'float', 186.4, null)
) as v(pid, t, n, s)
where d.device_type = 'METER';

-- 充电桩枪位（待机）
insert into device_property_latest (device_id, channel_no, property_id, value_type, value_num, value_text, value_time, updated_at)
select d.id, c.channel_no, v.pid, v.t, v.n, v.s, now(), now()
from device_instance d
join device_channel c on c.device_id = d.id and c.channel_type = 'CONNECTOR'
cross join (values
  ('gunState', 'string', null::numeric, 'idle'),
  ('plugged', 'bool', 0, null),
  ('voltage', 'float', 0, null),
  ('current', 'float', 0, null),
  ('soc', 'int', 0, null),
  ('chargeEnergy', 'float', 0, null),
  ('chargeAmount', 'float', 0, null)
) as v(pid, t, n, s)
where d.device_type = 'CHARGER';

-- ------------------------------------------------------------------ 告警规则种子

insert into alarm_rule (rule_code, name, product_id, level, metric_property, operator, threshold,
                        duration_seconds, enabled, description, created_at, updated_at)
values
 ('RULE-STOR-TEMP', '储能最高单体温度过高',
  (select id from product where product_code = 'PROD-STORAGE-215'), 'CRITICAL', 'cellMaxTemp', 'gt', 55, 30,
  true, '超过 55℃ 持续 30 秒触发', now(), now()),
 ('RULE-STOR-SOC', '储能 SOC 偏低',
  (select id from product where product_code = 'PROD-STORAGE-215'), 'WARN', 'soc', 'lt', 15, 60,
  true, 'SOC 低于 15% 持续 60 秒触发', now(), now()),
 ('RULE-STOR-DIFF', '储能单体压差偏大',
  (select id from product where product_code = 'PROD-STORAGE-215'), 'WARN', 'cellVoltageDiff', 'gt', 80, 120,
  true, '压差超过 80mV 持续 2 分钟触发', now(), now()),
 ('RULE-PV-TEMP', '光伏机内温度过高',
  (select id from product where product_code = 'PROD-PV-100K'), 'MAJOR', 'cabinetTemp', 'gt', 70, 60,
  true, '机内温度超过 70℃ 持续 60 秒触发', now(), now()),
 ('RULE-WIND-VIB', '风机振动超限',
  (select id from product where product_code = 'PROD-WIND-2M'), 'MAJOR', 'vibration', 'gt', 7.1, 30,
  true, '振动幅值超过 7.1 mm/s 触发', now(), now()),
 ('RULE-WIND-WIND', '风速超切出值',
  (select id from product where product_code = 'PROD-WIND-2M'), 'WARN', 'windSpeed', 'gt', 25, 10,
  true, '风速超过 25m/s 持续 10 秒触发', now(), now()),
 ('RULE-CHG-LINE-TEMP', '充电枪线温度过高',
  (select id from product where product_code = 'PROD-CHARGER-YKC'), 'MAJOR', 'gunLineTemp', 'gt', 75, 5,
  true, '枪线温度超过 75℃ 触发', now(), now()),
 ('RULE-CAM-STORAGE', '摄像机存储将满',
  (select id from product where product_code = 'PROD-CAMERA-BULLET'), 'WARN', 'storageUsedPercent', 'gt', 90, 300,
  true, '存储占用超过 90% 触发', now(), now()),
 ('RULE-LOCK-BATTERY', '地锁电量低',
  (select id from product where product_code = 'PROD-LOCK-SMART'), 'WARN', 'batteryPercent', 'lt', 20, 0,
  true, '电量低于 20% 触发', now(), now());

-- ------------------------------------------------------------------ 费率与用户

insert into rate_model (model_no, sharp_energy, sharp_service, peak_energy, peak_service,
                        flat_energy, flat_service, valley_energy, valley_service, loss_ratio,
                        segments, active, updated_at)
values ('0001', 1.20000, 0.40000, 1.00000, 0.35000, 0.80000, 0.30000, 0.50000, 0.20000, 0,
        '3,3,3,3,3,3,3,3,3,3,3,3,2,2,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,2,2,2,2,2,2,0,0,0,0,0,0,2,2,2,2,2,2',
        true, now());

insert into ops_user (username, password_hash, role, enabled, created_at) values
 ('admin',  '$2b$10$Xxf4Jym1fKaqUZ2yySQb1elVlpPBB0YBp0Sr58IoHq1X8Mg3TDphC', 'ADMIN',  true, now()),
 ('ops',    '$2b$10$02RHJr9H23NhZIrEENDT7e.Ob1zn49a5Qdlw3UzZ3YE.nXPhn9TKW', 'OPS',    true, now()),
 ('viewer', '$2b$10$FtqEK6vxE5FV6EiFEhdw2eFJ6rhrDdDK7jO8LMN3OXuSloqehDIFi', 'VIEWER', true, now());

-- ------------------------------------------------------------------ 协议文档

insert into protocol_doc (protocol, version, title, source, created_at)
values ('YKC16', '1.6', '云快充平台协议 V1.6', '云快充平台协议V1.6新.pdf', now()),
       ('OCPP16J', '1.6', 'OCPP 1.6J 协议要点', 'docs/ocpp16.md', now());

insert into protocol_doc_section (doc_id, protocol, version, frame_or_action, heading, content, locator, keywords)
values
 ((select id from protocol_doc where protocol='YKC16' and version='1.6'),
  'YKC16', '1.6', '0x3B', '交易记录',
  '0x3B 为充电桩上送交易记录。平台收到后应保存账单，并向桩返回 0x3B 对应的确认帧。交易流水号用于与平台订单关联，平台侧必须按设备与流水号幂等处理。',
  '云快充 1.6 / 4.4 交易记录', '0x3B,交易记录,账单,流水号'),
 ((select id from protocol_doc where protocol='YKC16' and version='1.6'),
  'YKC16', '1.6', '0x40', '交易记录确认',
  '平台确认交易记录后向桩返回确认帧。若平台未确认，桩可能重传交易记录；平台必须保证重复上送只生成一笔账单。',
  '云快充 1.6 / 4.4 交易记录确认', '0x40,交易确认,重传,幂等'),
 ((select id from protocol_doc where protocol='YKC16' and version='1.6'),
  'YKC16', '1.6', '0x31', '充电桩主动申请启动充电',
  '0x31 由桩发起，平台返回 0x32 启动确认。未插枪时桩返回失败原因 0x05，平台应在 60 秒内保留启动条件。',
  '云快充 1.6 / 6.1 启动充电', '0x31,0x32,启动,未插枪'),
 ((select id from protocol_doc where protocol='OCPP16J' and version='1.6'),
  'OCPP16J', '1.6', 'BootNotification', '启动通知',
  'BootNotification 携带 chargePointVendor、chargePointModel、chargePointSerialNumber 与 firmwareVersion，平台据此建立或更新设备档案。',
  'OCPP 1.6J / BootNotification', 'BootNotification,建档,厂商,型号'),
 ((select id from protocol_doc where protocol='OCPP16J' and version='1.6'),
  'OCPP16J', '1.6', 'StartTransaction', '开始交易',
  'StartTransaction 携带 connectorId、idTag、meterStart 与 timestamp。平台返回 transactionId 与 idTagInfo.status，并在订单中保存整数交易号。',
  'OCPP 1.6J / StartTransaction', 'StartTransaction,交易,meterStart'),
 ((select id from protocol_doc where protocol='OCPP16J' and version='1.6'),
  'OCPP16J', '1.6', 'StopTransaction', '结束交易',
  'StopTransaction 携带 transactionId、meterStop、timestamp 与 reason。平台据此结算订单并生成账单；若订单已结算，重复消息应返回接受且不重复计费。',
  'OCPP 1.6J / StopTransaction', 'StopTransaction,结算,账单,幂等');

insert into audit_log (actor, action, target_type, result, detail, created_at)
values ('system', 'schema.init', 'database', 'SUCCESS', 'V1 物模型重建完成', now());