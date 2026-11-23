# Swagger / OpenAPI 注解约定

适用范围：Spring Boot 4 + Springdoc + Knife4j 的 HTTP 服务。注解只补充 Springdoc 无法从方法签名、参数名、
类型和校验规则推导的信息；能从代码结构得到的内容不重复写在注解里。

## Controller

- 类级使用 `@Tag(name, description)` 表达业务分组，一个 Controller 对应一个业务域。
- 方法级使用 `@Operation(operationId, summary)`；只有行为不明显时才补 `description`。
- 路径变量、查询参数和普通请求头不逐一手写 `@Parameter`。参数名、必填性、默认值和类型由 Springdoc 自动推导。
- 过滤器注入的 `@RequestAttribute` 使用 `@Parameter(hidden = true)`，避免把内部认证对象暴露成公开参数。
- 标准 Bearer 和固定服务令牌使用 `SecurityScheme` + 类级 `@SecurityRequirement`，不在每个方法重复请求头。
- Runtime 的四个 HMAC 请求头由 `OpenApiConfig` 统一注入，Controller 不重复声明。
- `@ApiResponse` 只用于框架无法推导的状态码，例如异步受理的 `202`；成功响应默认由返回类型生成。
- `400`、`401`、`500` 等公共错误由 `OpenApiConfig` 统一注入 `ApiError` schema，方法上不再重复大段响应声明。
- 普通 JSON 接口声明 `produces = application/json`；同时支持 JSON 与 SSE 的接口保留多个媒体类型。

## DTO 与 Schema

- 请求 record 使用 `@Schema(name, description)` 说明整体用途，不逐字段堆 description 和 example。
- 必填字段使用 `@Schema(requiredMode = REQUIRED)`；敏感字段使用 `format = "password"` 或 `hidden = true`。
- 只有在字段名无法表达取值集合时才使用 `allowableValues`；长度、大小等约束以实际校验逻辑为准。
- 错误响应统一为 `{ "error": "...", "message": "..." }`，由 `ApiError` 和全局 OpenAPI 配置维护。
- 领域内部 record 放 `domain`，HTTP 请求/响应 record 放 `dto`；仅类内强耦合的 private record 可以留在所属类中。

## 全局配置

- 提供 `OpenAPI` Bean，写 `title`、`version`、`description`，并注册公共 `ApiError` schema。
- `springdoc.paths-to-match` 必须覆盖全部业务前缀；新增 `/v1/**`、`/internal/**` 等入口时同步检查。
- `operationId` 必须全局唯一。Knife4j 5.7.9 会用 `Controller_method` 覆盖显式 `operationId`，由 `OpenApiConfig`
  保留显式值；升级 Knife4j 后应复核 `/v3/api-docs`。
- 生产环境按部署策略关闭或隔离文档端点：`springdoc.api-docs.enabled=false`、`springdoc.swagger-ui.enabled=false`。

## 验证

启动服务后检查 `/v3/api-docs`：

- 每个 `@RequestMapping` 方法都有唯一 `operationId`；
- 路径、查询、请求头参数仍能从方法签名和全局配置生成；
- 请求 DTO 出现在 `components.schemas`，敏感字段不出现或为 `password`；
- 公共 4xx/5xx 响应使用统一 `ApiError` schema；
- `/v1/**` 等非 `/api/**` 入口没有被 `paths-to-match` 漏掉。
