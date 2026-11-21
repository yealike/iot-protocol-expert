# 服务器部署（Docker Compose）

目录约定：

| 路径 | 用途 |
|---|---|
| `deploy/docker-compose.yml` | 7 个服务：6 个 Spring Boot + 1 个 nginx |
| `deploy/docker/Dockerfile.service` | 通用 JRE 21 运行镜像 |
| `deploy/web/` | 前端静态站 + 反向代理配置 |
| `deploy/build-env.ps1` | 由 `.env.local` 生成服务器 `.env` |
| `deploy/deploy.ps1` | 一键构建、上传、重启、健康检查 |

## 一键部署

```powershell
# 首次：生成服务器配置（Langfuse 密钥从 deploy\.env.langfuse 读取）
.\deploy\build-env.ps1

# 之后每次改动
.\deploy\deploy.ps1 -ServerHost <内网地址> -ServerUser <账号> -RemoteDir /home/<账号>/iot-protocol-expert/deploy
```

常用开关：

- `-SkipBuild`：不重新执行 Maven，直接上传现有 JAR
- `-SkipWeb`：不改前端
- `-HealthTimeoutSeconds 0`：只上传不重启（用于调试目录）

服务器地址与账号不写进仓库，脚本里的默认值是占位符，不显式传入会直接报错退出。

部署完成后：

- 工作台：<http://SERVER_IP:8095>
- Langfuse：<http://SERVER_IP:3005>
- 设备接入：`8768`（云快充）、`8769`（OCPP）

## 三个容易踩的坑

- **nginx 上游缓存**：`ipe-web` 启动时解析 `ipe-ops` / `ipe-control` 的容器 IP。只重建某一个后端时
  （`docker compose up -d ipe-control`），nginx 仍指向旧 IP，页面会 502。执行
  `docker compose restart ipe-web` 即可刷新。`deploy.ps1` 全量重建不受影响。
- **MCP 依赖 Nacos 服务发现**：Higress 的 `ipe-ops-rest` 通过 Nacos 服务名
  `iot-protocol-expert-ops` 找实例，因此 `ipe-ops` 单独开启了
  `IPE_NACOS_DISCOVERY_ENABLED=true` 并以 `IPE_OPS_ADVERTISE_IP=SERVER_IP` 注册；
  否则 MCP 工具会返回 503。`ipe-ops` 的宿主机端口也需对内网开放（`HOST_BIND_IP`），
  否则 Higress 容器访问不到。
- **Maven 增量构建残留**：`target/classes` 不会自动删除已在源码中移除的资源文件，
  过期的 MyBatis 映射 XML 会被打进 JAR，导致容器启动时报
  `Failed to parse mapping resource` 并反复重启。`deploy.ps1` 的打包已固定带 `clean`，
  手工执行 Maven 时也要用 `mvn clean package`。

## 服务器侧

```bash
cd /home/SERVER_USER/iot-protocol-expert/deploy
docker compose ps
docker compose logs -f ipe-control
docker compose up -d --build ipe-control   # 单服务重建
docker compose down                        # 停止（保留 ./data）
```

`.env` 含数据库、模型、Langfuse 等密钥，权限保持 600，不要提交。

## IDEA 怎么用

推荐两条路，按 IDEA 版本选：

1. **Ultimate + Docker 插件**：`Settings → Build, Execution, Deployment → Docker` 新增 SSH 连接
   （`SERVER_USER@SERVER_IP`，密钥 `%USERPROFILE%\.ssh\id_ed25519`）；然后在项目里右键
   `deploy/docker-compose.yml` → `Run`，服务和日志直接出现在 Services 面板。
2. **任何版本**：`Settings → Tools → External Tools` 加一个工具，Program 填
   `powershell.exe`，Arguments 填 `-ExecutionPolicy Bypass -File "$ProjectFileDir$\deploy\deploy.ps1"`，
   之后工具菜单里一键部署。

不要用 IDEA 的 Kubernetes 插件部署这套服务：当前是单机 Compose，K8s 只会增加维护成本。
