# iot-protocol-expert-web

充电桩运营平台前端，使用 React、TypeScript、Ant Design 和 Vite。

在本目录执行 `npm ci` 后，使用 `npm run dev` 启动开发服务；默认端口 5173，API 代理至运营平台的 19082。使用 `npm run build` 生成 `dist/`，并将 `/api`、`/login`、`/logout`、`/actuator` 代理到运营平台。

页面包括运营总览、充电桩设备、产品与物模型、告警。会话通过后端登录接口管理。
