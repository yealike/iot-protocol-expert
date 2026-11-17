# 共享库

这些模块只提供库，不启动进程，也不依赖业务应用模块。根 POM 直接聚合，执行根目录 Maven 命令即可构建。

| 模块 | 边界 |
| --- | --- |
| iot-protocol-expert-device-codec | 协议帧、编码解码、流拆帧；供 OPS、网关、模拟器使用 |
| iot-protocol-expert-runtime-contract | Control/Runtime DTO、摘要、签名、组件路径和 OPS 身份视图；生产代码仅依赖 JDK |
| iot-protocol-expert-model-contract | 模型调用凭据与计量契约；生产代码仅依赖 JDK |
| iot-protocol-expert-observability | 日志与 OTel 默认配置、协议链路观测；不扫描或装配业务服务 |

协议观测通过显式 `@Import` 接入。网关通过 `ProtocolTelemetry.TimeoutFailure` 提供超时分类，共享库不引用网关异常实现。
