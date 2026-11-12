# iot-protocol-expert-web

充电桩运营平台前端，使用 React、TypeScript、Ant Design 和 Vite。

在本目录执行 `npm ci` 后，使用 `npm run dev` 启动开发服务；默认端口 5173，API 代理至运营平台的 19082。使用 `npm run build` 生成 `dist/`，并将 `/api`、`/login`、`/logout`、`/actuator` 代理到运营平台。

页面包括运营总览、充电桩设备、产品与物模型、告警。会话通过后端登录接口管理。

## 充电场景模型

模拟器页面使用 Three.js 加载 Blender 模型 `public/simulator/simulator-model.glb`，可编辑模型为 `tools/simulator-model.blend`。模型为比例化示意：车辆参考 [Tesla Model Y 官方尺寸](https://www.tesla.com/ownersmanual/modely/en_eu/GUID-1E76B638-7B12-4D9A-8767-94B7F1E92A0E.html)，直流桩参考 [ABB Terra 124 CE 数据表](https://library.e.abb.com/public/48ca18f6f24f470f8321774850d6d6a5/Txx4%20Gen%202%20Data%20Sheet.pdf)（最大 120 kW，约 1900 × 880 × 565 mm）。充电枪、SOC 和功率以当前选中枪的仿真状态为准。
