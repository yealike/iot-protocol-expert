param(
    [switch]$EnableOtlp,
    [string]$OtlpEndpoint = $env:IPE_OTLP_ENDPOINT,
    [string]$JavaHome = $env:IPE_JAVA_HOME,
    [long]$DeviceId = 0
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env-local'
if (!(Test-Path -LiteralPath $envFile)) { throw '缺少 .env-local，请从 .env.example 创建并填写本地配置' }
# 只解析 KEY=VALUE，不执行文件内容；显式传入的脚本参数仍优先。
foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    $line = $line.Trim()
    if (!$line -or $line.StartsWith('#')) { continue }
    if ($line -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { throw ' .env-local 中存在无效配置行' }
    $key, $value = $matches[1], $matches[2].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}
if (!$PSBoundParameters.ContainsKey('OtlpEndpoint')) { $OtlpEndpoint = $env:IPE_OTLP_ENDPOINT }
if (!$PSBoundParameters.ContainsKey('JavaHome')) { $JavaHome = $env:IPE_JAVA_HOME }
if (!$EnableOtlp -and $env:IPE_OTLP_ENABLED -eq 'true') { $EnableOtlp = $true }
if (!$OtlpEndpoint) { $OtlpEndpoint = 'http://127.0.0.1:4318' }
$OtlpEndpoint = $OtlpEndpoint.TrimEnd('/')
if (!$JavaHome) { $JavaHome = $env:JAVA_HOME }
if (!$JavaHome) { throw '请将 JAVA_HOME / IPE_JAVA_HOME 或 -JavaHome 设置为 JDK 21 安装目录' }
$java = Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "未找到 JDK 21：$java" }
$ops = Join-Path $root 'iot-protocol-expert-ops\target\iot-protocol-expert-ops-1.0.0.jar'
$platform = Join-Path $root 'iot-protocol-expert-protocol\target\iot-protocol-expert-protocol-1.0.0-exec.jar'
$simulator = Join-Path $root 'iot-protocol-expert-simulator\target\iot-protocol-expert-simulator-1.0.0.jar'
$ai = Join-Path $root 'iot-protocol-expert-ai\target\iot-protocol-expert-ai-1.0.0.jar'
$logDir = Join-Path $root 'logs'
$runDir = Join-Path $root 'run'
New-Item -ItemType Directory -Force -Path $logDir, $runDir, (Join-Path $root 'data') | Out-Null

# 服务间地址与身份。数据库地址默认在 ops 的 application.yml 中，可用 IPE_OPS_DB_* 覆盖。
if (!$env:IPE_PROTOCOL_DOCUMENT_DIR) { $env:IPE_PROTOCOL_DOCUMENT_DIR = Join-Path $root 'data\protocol-documents' }
if (!$env:IPE_OPS_BASE_URL) { $env:IPE_OPS_BASE_URL = 'http://127.0.0.1:19082' }
if (!$env:IPE_PROTOCOL_BASE_URL) { $env:IPE_PROTOCOL_BASE_URL = 'http://127.0.0.1:19080' }
if (!$env:IPE_OPS_SERVICE_TOKEN) { $env:IPE_OPS_SERVICE_TOKEN = 'local-service-token' }
if (!$env:IPE_OPS_SIMULATOR_TOKEN) { $env:IPE_OPS_SIMULATOR_TOKEN = 'local-simulator-token' }
if ($DeviceId -gt 0) { $env:IPE_SIMULATOR_DEVICE_ID = "$DeviceId" }
if ($EnableOtlp) { $env:IPE_OTLP_ENABLED = 'true'; $env:IPE_OTLP_ENDPOINT = $OtlpEndpoint }
else { $env:IPE_OTLP_ENABLED = 'false' }

function Start-App($name, $jar, $port) {
    if (!(Test-Path -LiteralPath $jar)) { throw "缺少 $jar，请先 mvn package" }
    $pidFile = Join-Path $runDir "$name.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $oldId = [int](Get-Content -LiteralPath $pidFile)
        $old = Get-CimInstance Win32_Process -Filter "ProcessId=$oldId" -ErrorAction SilentlyContinue
        if ($old -and $old.CommandLine.Contains($jar)) {
            throw "$name 已在运行（PID $oldId）；如需加载新版本，请先运行 scripts/stop-local.ps1"
        }
    }
    $javaArgs = @('-jar', ('"' + $jar + '"'), '--spring.profiles.active=local', '--server.address=127.0.0.1',
        '--logging.level.org.springframework=WARN', '--logging.level.org.springframework.web=WARN', '--logging.level.org.springframework.boot=WARN',
        '--logging.level.org.springframework.boot.autoconfigure=WARN',
        '--logging.level.org.springframework.http=WARN', '--logging.level.org.hibernate=WARN')
    if ($EnableOtlp) {
        $javaArgs += @('--management.tracing.export.otlp.enabled=true', '--management.logging.export.otlp.enabled=true',
            '--management.otlp.metrics.export.enabled=true',
            "--management.opentelemetry.tracing.export.otlp.endpoint=$OtlpEndpoint/v1/traces",
            "--management.opentelemetry.logging.export.otlp.endpoint=$OtlpEndpoint/v1/logs",
            "--management.otlp.metrics.export.url=$OtlpEndpoint/v1/metrics")
    }
    $out = Join-Path $logDir "$name.out.log"
    $err = Join-Path $logDir "$name.err.log"
    $proc = Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $root -WindowStyle Hidden `
        -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $proc.Id | Set-Content -LiteralPath $pidFile
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($proc.HasExited) { throw "$name 启动失败，请查看 $out 和 $err" }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { Write-Host "$name 已就绪，PID $($proc.Id)"; return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "$name 健康检查超时，请查看 $out"
}

Start-App 'ops' $ops 19082
# AI 服务复用 ops 数据库（独立 ipe_ai schema），身份校验转发到 ops 的会话入口
Start-App 'ai' $ai 19083
Start-App 'protocol' $platform 19080
Start-App 'simulator' $simulator 19081
Write-Host '运维平台 API http://localhost:19082 （admin/admin123、ops/ops123、viewer/viewer123）'
Write-Host '运维平台前端 http://localhost:5173（在 iot-protocol-expert-web 下 npm run dev）'
Write-Host 'AI 助手 API 19083；前端入口 /assistant（模型默认关闭，可用 IPE_MODEL_* 启用）'
Write-Host '协议通道 19080（TCP 8768 / ws 8769）；模拟器 API 19081（页面位于运维平台）'
Write-Host '最近链路 /actuator/telemetry ；指标 /actuator/prometheus ；OTLP 默认关闭'
