# Git 历史脱敏与部署说明

本次清理覆盖服务器地址在所有可控分支历史中的出现，包括示例配置、旧文档和种子 SQL。替换后的 `192.0.2.10` 属于文档示例地址，不能作为实际设备或服务配置。

## 开发者同步

历史 SHA 已改变。其他开发者优先重新克隆仓库；旧克隆有未提交工作时，先单独导出补丁并检查脱敏，再应用到新克隆。不要把旧分支直接 merge 或 push 回 GitHub，否则可能再次引入已清理历史。旧备份只在本机受控保存，不上传、不推送。

## 已部署数据库

为了清除敏感原文，本次历史清理例外修改 OPS 的 V1、V3 种子脚本，并同步代码规范 SHA 基线。其他已发布迁移不做修改。

已有数据库的 Flyway 校验和需要在应用停止期间由管理员核对并修复。先备份数据库，再用既有数据库认证方式执行 `scripts/maintenance/repair-sanitized-flyway-checksums.sql`，最后启动应用。脚本只接受指定版本、脚本名、成功状态及已知旧/新校验和；发现不同值立即失败并回滚。该脚本只调整两行历史校验和，不更新设备配置、不执行全量 repair，也不关闭迁移校验。

新数据库直接使用清理后的迁移，不需要执行修复脚本。本次没有连接或修改任何环境数据库。

## 本地提交与推送检查

每个新克隆执行：

```sh
git config core.hooksPath .githooks
python scripts/scan-sensitive.py --staged
python scripts/scan-sensitive.py --all-history
```

提交扫描整个暂存快照；推送扫描将要推送提交的全部可达历史，覆盖正文、提交及标签元数据。扫描器不会打印敏感值，覆盖常见访问令牌、私钥及服务器 IPv4（包括 RFC1918、公网和共享地址段）。文档示例地址和本机回环地址允许使用；测试代码中的网络边界地址仅豁免地址规则，凭据规则仍生效。规则扫描不能证明没有任何秘密：真实密码、域名、业务数据及新令牌格式仍需要人工审阅，配置应使用环境变量并排除本地秘密文件。仓库的运行时 Trace 内容采集策略不受发布检查影响。

## GitHub 残留对象

强制更新分支只能清除可控引用。旧提交直链、PR 引用及缓存可能仍可访问，需要 GitHub Support 配合清理；他人的 clone/fork/下载副本无法由本仓库撤回。官方说明：https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository
