# 物模型与设备域设计

本文描述 iot-protocol-expert 从「充电桩专用平台」升级为「多设备能源运维平台」后的领域模型，
并给出与 jetlinks 的取舍对照。**模型先行**：所有页面、API、3D 锚点都由这份模型驱动。

## 1. 为什么要改

改造前的模型把协议字段焊进了业务表：

```sql
-- 改造前
device(pile_type, network_type, sim_card, operator, charge_point_vendor, ...)
gun_state(device_id, gun_no, voltage, current, soc, ...)
```

后果：

| 现象 | 根因 |
|---|---|
| 加一台风机要改表结构 | 没有「产品」层，设备类型由字段组合隐含表达 |
| 充电桩与风机无法共用监测页 | 实时值表是「枪」专用，不是「属性」专用 |
| 物模型无法表达单位/上下限/枚举 | 值类型信息只存在于 Java 字段和前端 if 里 |
| 前端每个页面手写表格列 | 没有可被渲染的元数据 |
| 告警只能写死判断 | 没有指标 + 规则 + 记录的分离 |
| 3D 场景与设备台账对不上 | 台账没有空间锚点概念 |

## 2. 与 jetlinks 的取舍对照

| 概念 | jetlinks | 本项目 | 差异与理由 |
|---|---|---|---|
| 产品 | `dev_product.metadata` 存物模型 JSON | `product.metadata` 存物模型 JSON | 采纳。物模型不建表，避免改模型要 DDL |
| 设备实例 | `dev_device_instance`，`derive_metadata` 覆盖产品 | `device_instance.derive_metadata` | 采纳，但默认不写；同一套 `merge` 语义 |
| 标签 | `dev_device_tags`，主键 `md5(deviceId:key)` | `device_tag`，唯一键 `(device_id, tag_key)` | 采纳思路，主键用自增（PG 不需要 md5 去重） |
| 通道/连接器 | 连接器只是属性名的一部分 | **显式 `device_channel`** | 加强。枪/组串/电池簇/镜头同构，显式建模后前端与 3D 都能按通道定位 |
| 实时值 | 注册中心缓存 + 时序库 | `device_property_latest` 落 PG | 简化：规模不大时 PG 足够，保留时序表以便后续换库 |
| 时序 | TDengine / Timescale 策略 SPI | `device_property_ts`（PG） | 先落地，`StorageStrategy` 接口预留 |
| 网络/协议/网关三段 | Network + Protocol + DeviceGateway | 保留现有 `ProtocolServer` SPI，把协议与传输降为产品字段 | 现有通道只有云快充/OCPP，抽象成 Provider 注册表收益低；先做「配置驱动」，不做「插件热插拔」 |
| 设备分组 | 通用关系表 `RelationEntity` | `device_tag` + `station_id` | 关系表是 jetlinks 为多租户资产树做的，本项目用站点 + 标签足够；不引入第二套树 |
| 告警 | 场景规则输出告警 | `alarm_rule` + `alarm_record` | 采纳「规则与记录分离」，暂不做通用场景引擎 |
| 生命周期 | `deploy/undeploy` + 注册中心 | `state` 状态机 + 协议通道回写 | 采纳状态语义（`NOT_ACTIVE/ONLINE/OFFLINE/FAULT`） |

**明确不抄的两处**：ES/Redis 的复杂存储策略、通用关系/资产树。规模不到，引入即负债。

## 3. 领域模型

### 3.1 关系

```
device_category (品类树：发电 / 储能 / 充电 / 安防 / 计量)
   1
   N
product (产品 = 物模型模板 + 协议接入方式)
   1
   N
device_instance (设备实例)
   1 ──N device_tag           (扩展属性，如厂商、额定功率、SIM 卡)
   1 ──N device_channel       (通道：枪 / MPPT 组串 / 电池簇 / 镜头)
   1 ──N device_property_latest (每个通道每个属性的最新值)
   1 ──N device_property_ts     (时序)
   1 ──N device_event_log       (事件)
   1 ──N alarm_record           (告警记录)
   1 ──N charge_order           (充电订单，仅 CHARGER)
   1 ──N bill                   (账单，仅 CHARGER)
   1 ──N frame_log / command_record / trouble_case  (运维域)

alarm_rule (规则，可挂在产品上批量生效，也可覆盖到单设备)
   1 ──N alarm_record
```

### 3.2 关键取舍

1. **冗余字段换查询**：`device_instance` 冗余 `device_type`、`protocol`、`station_id`，
   列表页不 join `product`。产品改协议时由服务层同步刷新冗余字段。
2. **物模型只存 JSON**：`product.metadata`。属性/事件/功能不建独立元数据表——
   改模型不需要 DDL，代价是无法用 SQL 直接按属性过滤（由 `device_property_latest` 承担）。
3. **通道是显式的**：不要用 `soc_01`、`soc_02` 这类拼字段。通道号 + 属性名是二维主键。
4. **充电域保留专属表**：`charge_order`、`bill`、`rate_model` 是充电业务语义，
   不做「通用订单」抽象。通用性是设备与实时数据，不是计费。
5. **状态四值**：`NOT_ACTIVE`（未激活/未登记连接）、`OFFLINE`、`ONLINE`、`FAULT`。
   `online` 不再单独存布尔，列表过滤走 `state in ('ONLINE','FAULT')`。

## 4. 物模型（ThingModel）

### 4.1 结构

```json
{
  "properties": [
    {
      "id": "activePower",
      "name": "有功功率",
      "valueType": { "type": "float", "min": -500, "max": 500, "unit": "kW", "scale": 2 },
      "channel": false,
      "readOnly": true,
      "description": "正为发电/放电，负为用电/充电"
    }
  ],
  "functions": [
    {
      "id": "setPowerLimit",
      "name": "设置功率上限",
      "async": true,
      "inputs": [
        { "id": "percent", "name": "上限百分比", "valueType": { "type": "int", "min": 0, "max": 100 } }
      ],
      "output": { "type": "bool" }
    }
  ],
  "events": [
    {
      "id": "overTemp",
      "name": "过温告警",
      "level": "WARN",
      "valueType": {
        "type": "object",
        "properties": [
          { "id": "temperature", "name": "温度", "valueType": { "type": "float", "unit": "℃" } }
        ]
      }
    }
  ],
  "tags": [
    { "id": "ratedPowerKw", "name": "额定功率", "valueType": { "type": "int", "unit": "kW" } }
  ]
}
```

- `properties[].channel = true` 表示该属性按通道上报（枪电压、组串电流、电池簇 SOC）。
  通道型属性的存储键是 `(device_id, channel_no, property_id)`。
- `functions[].inputs/output` 复用同一套 `valueType`。
- `events[].level` 给出默认告警级别（`INFO/WARN/MAJOR/CRITICAL`）。

### 4.2 值类型系统

| type | 参数 | PG 落地 |
|---|---|---|
| `int` / `long` | `min` `max` `unit` | `value_num` |
| `float` / `double` / `decimal` | `min` `max` `unit` `scale` | `value_num` |
| `string` | `maxLength` | `value_text` |
| `bool` | — | `value_num` (0/1) |
| `date` / `dateTime` | `format` | `value_time` |
| `enum` | `elements[{value,text}]` | `value_text` |
| `object` | `properties[]` | `value_json` |
| `array` | `elementType` | `value_json` |
| `geo` | — | `value_json` |

落地规则：**一个属性一行，按类型选列**。查询用 `value_num`，展示用 `value_text`/`value_json`。

### 4.3 设备类型与默认物模型

| device_type | 中文 | 通道 | 核心属性 | 核心功能 | 核心事件 |
|---|---|---|---|---|---|
| `CHARGER` | 充电桩 | `CONNECTOR`（枪） | 功率、枪状态、插枪、电压、电流、SOC、电量、金额 | 启动/停止/解锁/设费率/重启 | 启动、停止、故障、账单上传 |
| `PV` | 光伏逆变器 | `STRING`（组串） | 辐照度、直流电压/电流、交流功率、日/总发电量、机温、效率 | 功率限值、启停 | 过温、组串故障、离网 |
| `WIND` | 风机 | 无 | 风速、转速、有功功率、日/总发电量、机舱温度、偏航角 | 启停、偏航、复位 | 切入/切出、振动、刹车故障 |
| `STORAGE` | 储能 | `PACK`（簇） | SOC、SOH、有功功率、母线电压/电流、最高/最低单体温度、压差、循环次数 | 功率设定、SOC 设定、启停 | 过温、一致性差、保护动作 |
| `LOCK` | 智能地锁 | 无 | 状态、电量、信号、车牌 | 降锁/升锁/常开 | 降锁、升锁、卡滞、低电量 |
| `GATE` | 门禁道闸 | 无 | 状态、最近车牌、通行次数 | 开闸/关闸/常开 | 车辆通行、识别、非法入侵 |
| `CAMERA` | 摄像头 | `LENS` | 在线、码流、分辨率、存储占用 | 抓拍、录像、云台 | 识别、移动侦测、存储满 |
| `METER` | 电表 | `PHASE`（相） | 电压、电流、功率、正向/反向电量 | 抄表 | 失压、失流 |

`scene_model` 字段给出该类型在 3D 场景中的默认节点名：`PV`/`Wind`/`Storage`/`Charger`/`Lock`/`GateIn`/`GateOut`/`CameraIn`/`CameraOut`。
`device_instance.scene_anchor` 可覆盖单个设备的 3D 位置。

## 5. 表结构

见 `iot-protocol-expert-ops/src/main/resources/db/migration/V1__init.sql`。要点：

- 时间统一 `timestamp`（本地时区 Asia/Shanghai，`hibernate.jdbc.time_zone` 已配）。
- JSON 列统一 `jsonb`，由 `JsonbStringType`（`org.hibernate.usertype.UserType`）映射，
  Java 侧是 `String`，避免 Hibernate 7 与 Jackson 3 的 format mapper 兼容问题。
- 所有业务外键指向 `device_instance(id)`，**不再有 `device` 表**。
- 删除 `gun_state`，充电枪实时值改为 `device_property_latest` 的通道型属性。

## 6. API 约定

### 6.1 统一分页响应

```json
{ "content": [], "page": 0, "size": 20, "total": 128, "totalPages": 7 }
```

查询一律 `POST /api/ops/{resource}/query`，body 为过滤条件对象：

```json
{ "keyword": "SN-01", "deviceType": "WIND", "states": ["ONLINE"], "stationId": 1,
  "tagFilters": {"vendor": "Huawei"}, "page": 0, "size": 20, "sort": "lastSeen,desc" }
```

### 6.2 资源

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ops/products` | 产品列表（含物模型摘要） |
| GET | `/api/ops/products/{id}` | 产品详情（完整物模型） |
| POST | `/api/ops/products` | 新建产品 |
| PUT | `/api/ops/products/{id}` | 更新产品（含物模型） |
| GET | `/api/ops/products/device-types` | 设备类型目录（含默认物模型，用于「从模板新建」） |
| POST | `/api/ops/devices/query` | 设备分页查询 |
| GET | `/api/ops/devices/{id}` | 设备详情（档案 + 通道 + 属性 + 事件 + 告警 + 报文） |
| POST/PUT | `/api/ops/devices` | 新建/更新设备 |
| POST | `/api/ops/devices/{id}/deploy` | 激活（登记到运行时） |
| POST | `/api/ops/devices/{id}/undeploy` | 注销 |
| GET | `/api/ops/devices/{id}/properties` | 当前属性（按通道分组） |
| GET | `/api/ops/devices/{id}/properties/{propertyId}/series` | 时序 |
| GET | `/api/ops/devices/{id}/events` | 事件列表 |
| POST | `/api/ops/devices/{id}/command` | 下发功能调用 |
| GET | `/api/ops/alarms` | 告警记录分页 |
| POST | `/api/ops/alarms/{id}/ack` | 确认告警 |
| GET | `/api/ops/overview` | 首页总览（按 device_type 聚合） |

### 6.3 内部接口（协议通道 → 平台）

保持 `/internal/**` + `X-Ops-Service-Token` 不变，路径与 DTO 尽量不动，
由 `DeviceInstanceService` 内部翻译到新模型：

- `POST /internal/devices/login`：按 `(protocol, protocolDeviceId)` 定位实例，写 `ONLINE`、`lastSeen`、`sessionId`；
- `POST /internal/telemetry/realtime`：把云快充实时帧翻译成通道型属性；
- `POST /internal/telemetry/bill`、`/internal/orders/**`、`/internal/ocpp/**`：充电域，逻辑不变。

## 7. 3D 锚点

`device_instance.scene_anchor`：

```json
{ "model": "PV", "position": [-4.4, 2.3, -6.3], "scale": 1.0 }
```

- `model` 对应 Blender 导出节点名（`PV`、`Wind`、`Storage`、`Charger`、`Lock`、`GateIn`、`GateOut`、`CameraIn`、`CameraOut`）。
- 为空时按 `product.scene_model` → `device_type` 兜底。
- 前端 `CampusScene` 用 `setDeviceState(deviceId, { power, soc, online, alarm })` 驱动，
  高亮与标签读的是同一条设备记录，不再是两套数据。

## 8. 迁移说明

本次是 **V1 重建**（用户确认推倒重来）。执行方式：

```powershell
# 清库后由 Flyway 重建（会写入种子数据）
$env:IPE_OPS_DB_URL='jdbc:postgresql://127.0.0.1:55432/ipe_ops'
```

种子数据包含：1 个演示站点、8 个产品（7 类设备 + 电表）、以及覆盖 3D 沙盘的 10 台演示设备
（光伏、风机、储能、4 个充电桩、地锁、进出道闸、2 个摄像头）。
