# iot-protocol-expert-web

充电桩运营平台前端，使用 React、TypeScript、Ant Design 和 Vite。

在本目录执行 `npm ci` 后，使用 `npm run dev` 启动开发服务；默认端口 5173，API 代理至运营平台的 19082。使用 `npm run build` 生成 `dist/`，并将 `/api`、`/login`、`/logout`、`/actuator` 代理到运营平台。

页面包括运营总览、充电桩设备、产品与物模型、告警。会话通过后端登录接口管理。

## 充电场景模型

模拟器使用 Three.js 加载 `public/simulator/simulator-model.glb`，可编辑模型为 `tools/simulator-model.blend`。车辆采用 tonielpro520 的 **2021 Tesla Model Y** 网格（CC BY 4.0），按经典款官方尺寸校准；桩体按星星充电 **双子座120kW V3.2** 官方照片与 850 × 550 × 1550 mm 规格重建。图片、规格书、确定参数及来源见 [外观参考](tools/references/README.md)，发布署名见 [ASSET-CREDITS](public/simulator/ASSET-CREDITS.md)。

执行 `D:/tools/blender/blender.exe --background --python-exit-code 1 --python tools/build-simulator-model.py` 重建模型。开发服务打开 `/tools/preview-simulator.html` 可独立验收插拔枪、充电、故障及切枪状态；该页使用固定测试状态。React 与独立模拟器共用同一场景实现。充电枪、SOC 和功率以当前选中枪的仿真状态为准。

枪线使用 Ammo.js/Bullet 软体物理，按 5 m 长度模拟重力下垂、贴地摩擦与插拔拖动。实现和调研见 [枪线物理方案](tools/references/cable-physics-notes.md)；执行 `node --test tools/cable-physics.test.mjs tools/twin-geometry.test.mjs` 运行物理和动作顺序测试。
