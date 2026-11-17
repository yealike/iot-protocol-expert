# Higress REST-to-MCP 配置

`ops-rest-mcp-v1.json` 是原生 `mcp-server` 插件配置，可用于路由级插件；不是 Nacos `toolsMeta` 格式，也不是完整的 Kubernetes WasmPlugin 资源。

1. 在 Higress 中配置 OPS 服务来源，可使用静态服务、Kubernetes Service 或 Nacos Naming。选择 Naming 时才需要 OPS 注册；静态路由不依赖 Nacos。
2. 新建版本化路由，例如 `/mcp/ops/v1`，绑定 OPS 后端，再把本文件作为 `mcp-server` 插件配置。请求模板使用相对路径，沿用该路由选定的后端。
3. 保持 `/internal/ai/rest/*` 路径不被外部重写覆盖。插件通过 `argsToJsonBody` 将参数送入 JSON 请求体。`_ipe_context` 保留，由应用生成并由 OPS 校验；不要在插件中写死密钥，也不要由模型生成。
4. 配置网关访问认证与限流；测试 `initialize`、`tools/list` 和带有效上下文的 `tools/call`。本文件不包含网关认证插件配置。
5. 将实际可访问的完整 MCP URL 填入平台 MCP 草稿及系统级 `IPE_AI_MCP_URL`，测试并发布。原有工具的 `readOnlyHint` 不能替代平台工具执行类别审核。

本示例沿用原 8 个工具和 REST 映射，未向任何 Higress 实例部署，也未验证部署环境的插件版本。Schema/映射变化时保留旧路由，发布新的配置与平台 MCP/Agent 版本。

配置依据：[Higress MCP Server 插件官方文档](https://higress.cn/en/docs/ai/mcp-server/)。`../ipe-ops-rest-mcp.json` 仅保留为历史迁移来源。
