# Higress REST-to-MCP 配置

`ops-rest-mcp-v2.json` 和 `control-knowledge-rest-mcp-v1.json` 是原生 `mcp-server` 插件配置，可用于路由级插件；不是 Nacos `toolsMeta` 格式，也不是完整的 Kubernetes WasmPlugin 资源。`ops-rest-mcp-v1.json` 保留为旧路由历史配置。

1. 在 Higress 中配置 OPS 与 Control 服务来源，可使用静态服务、Kubernetes Service 或 Nacos Naming。
2. 新建 `/mcp/ops/v2`，绑定 OPS 后端并使用 `ops-rest-mcp-v2.json`。该路由只暴露 `resolve_device`、`list_device_frames`、`decode_device_frame`。
3. 新建 `/mcp/control/v1`，绑定 Control 后端并使用 `control-knowledge-rest-mcp-v1.json`。该路由只暴露 `search_protocol_knowledge`。
4. 两个 MCP 插件的 `server.config.serviceToken` 必须配置为同一个服务令牌，并与 Ops 的 `IPE_OPS_SERVICE_TOKEN`、Control 的 `IPE_AI_INTERNAL_SERVICE_TOKEN` 一致。插件会为该路由的后端 REST 请求注入 `X-Ops-Service-Token`；示例 JSON 只保留空占位，不提交真实密钥。故意复制到其他后端或移除该头会得到 401。
5. 保持 `/internal/ai/rest/*` 和 `/internal/ai/knowledge/*` 路径不被外部重写覆盖。`_ipe_context` 由 Runtime 注入并由目标服务验签且只能消费一次，模型不可见。
6. Control 知识 MCP 的组件端点策略必须声明 `contextMode=OPS` 或 `authMode=OPS_CONTEXT`，并配置与 Ops MCP 相同的上下文凭据，否则 Runtime 不会注入可信 actor。
7. 配置网关访问认证与限流；测试 `initialize`、`tools/list` 和带有效上下文的 `tools/call`。`readOnlyHint` 不能替代平台工具执行类别审核。
8. Agent 新版本只绑定上述四个工具和必要的 Skill 工具，不再绑定 `list_documents`、`read_document`。发布后新建会话验收。

服务器部署中，Higress 和 Control 加入 Docker 外部网络 `ipe-mcp-backend`。Control 的网络别名是 `ipe-control.mcp.internal`。在 Higress 中为该域名创建 DNS 服务来源，使用 HTTP 和端口 `19083`，并将协议知识 MCP 路由指向该服务来源。Control 容器重建后仍使用相同的域名。

每条 MCP 路由只启用一份 MCP Server 插件配置。若 `tools/list` 正常，但 `tools/call` 只返回文本 `success`，检查全局插件和路由级插件是否重复处理请求。部署后的验收需检查设备解析返回设备与协议文档 ID、知识检索返回文档片段、报文查询和解码返回实际数据。使用「协议排障专家」新建会话，既有会话继续使用创建时的发布版本。

Schema 或映射变化时保留旧路由，发布新的配置与平台 MCP/Agent 版本。

配置依据：[Higress MCP Server 插件官方文档](https://higress.cn/en/docs/ai/mcp-server/)。`../ipe-ops-rest-mcp.json` 提供 Ops v2 工具的 Nacos `toolsMeta` 格式。
