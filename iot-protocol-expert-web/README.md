# iot-protocol-expert-web

运维平台前端。Vite + React 19 + TypeScript + Ant Design 5 + TanStack Query + ECharts（按需引入）+ three。

## 与后端的关系

- 开发：`npm run dev`（5173），`/api`、`/login`、`/logout` 由 Vite 代理到 19082，不需要 CORS。
- 生产：`npm run build:ops` 直接把产物构建到 `../iot-protocol-expert-ops/src/main/resources/static/`，
  由 Spring Boot 托管在根路径；`PageController` 负责 history 路由回落。
- 会话：Spring Security 表单登录（`POST /login`，JSESSIONID）。前端不保存 token，
  只通过 `/api/ops/auth/me` 判断「我是谁」。

## 目录

```
src/
  api/           请求层与契约类型（与后端 DTO 一一对应）
  components/    布局、图表、状态标签
  pages/         总览 / 设备 / 设备详情 / 产品与物模型 / 告警 / 园区沙盘
  three/         3D 园区沙盘引擎（数据驱动，非时间轴脚本）
public/assets/   energy-campus.glb（由 blender-energy-demo 导出）
```

## 页面

| 路由            | 说明                                                               |
| --------------- | ------------------------------------------------------------------ |
| `/overview`     | 设备总数/在线/故障/告警、设备类型分布、最新告警                    |
| `/devices`      | 分页设备台账，按类型、状态、关键字过滤                             |
| `/devices/:id`  | 档案、标签、实时属性（按通道分组）、趋势、事件、告警、指令、物模型 |
| `/products`     | 产品列表 + 设备类型模板目录                                        |
| `/products/:id` | 物模型四段（属性/功能/事件/标签）与原始 JSON                       |
| `/alarms`       | 告警分页、确认、关闭                                               |
| `/campus`       | 光储充放园区 3D 沙盘，数据驱动，可被模拟器内嵌                     |

## 3D 沙盘

`src/three/campus.ts` 是从 `blender-energy-demo` 重写而来的**数据驱动**引擎，与原始 demo 的区别：

- 去掉「车辆驶入 → 插枪 → 离场」的时间轴脚本动画（那是演示脚本，不是真实遥测）；
- 新增 `setDevices(feeds)`：外部每次传入真实设备状态，场景把功率/SOC/风速/锁状态/闸状态映射到节点；
- 新增 `dispose()`：卸载时释放几何体、材质、纹理、渲染器与监听，避免 React 路由切换时泄漏 WebGL context；
- 节点按名称前缀匹配并支持序号（`Charger`、`Charger1`…），修复了原 demo 里 `CameraIn/CameraOut` 没有锚点的问题。

模型节点约定保持不变：`PV`、`Wind`、`Storage`、`Charger*`、`Lock*`、`GateIn`、`GateOut`、`CameraIn`、`CameraOut`。
