param(
    [Alias("?")]
    [switch]$Help,
    [switch]$Restart,
    [switch]$SkipBuild,
    [int]$StartupTimeoutSeconds = 180,
    [int]$PublicationTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Write-Host @"
用法:
  .\scripts\seed-ai.ps1 [-Restart] [-SkipBuild] [-StartupTimeoutSeconds 180] [-PublicationTimeoutSeconds 120]

说明:
  加载 .env.local（不回显值），构建 Runtime 与 Control，先启动 Runtime，
  等待 readiness 后再以 IPE_AI_BOOTSTRAP_ENABLED=true 启动 Control 并输出发布摘要。
  要求 OPS 已启动并注册 Nacos 服务 iot-protocol-expert-ops。

参数:
  -Restart        停止并重新启动脚本管理的 Control/Runtime
  -SkipBuild      跳过 Maven 构建，直接使用现有 jar
  -?              显示本帮助并退出
"@
    exit 0
}

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot "lib\ipe-ai-common.ps1")

$envFile = Join-Path $root ".env.local"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "缺少 .env.local，请从 .env.example 创建并填写本地配置"
}
Import-IpeDotEnv -Path $envFile

$javaHome = Get-IpeJavaHome
$mvn = Get-IpeMavenCommand
$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

$env:IPE_AI_BOOTSTRAP_ENABLED = "true"
if (-not $env:IPE_AI_REGISTRY_ENABLED) {
    if ($env:IPE_NACOS_SERVER_ADDR -and ($env:IPE_AI_MCP_URL -or $env:IPE_AI_MCP_GATEWAY_BASE)) {
        $env:IPE_AI_REGISTRY_ENABLED = "true"
    } else {
        $env:IPE_AI_REGISTRY_ENABLED = "false"
    }
}
if (-not $env:IPE_AI_MCP_REGISTRY_ENABLED) {
    $env:IPE_AI_MCP_REGISTRY_ENABLED = $env:IPE_AI_REGISTRY_ENABLED
}
if (-not $env:IPE_NACOS_DISCOVERY_ENABLED) { $env:IPE_NACOS_DISCOVERY_ENABLED = "false" }
if (-not $env:IPE_INTERNAL_SERVICE_NAME) { $env:IPE_INTERNAL_SERVICE_NAME = "control" }
if (-not $env:IPE_RUNTIME_SERVICE_NAME) { $env:IPE_RUNTIME_SERVICE_NAME = "runtime" }
if (-not $env:IPE_RUNTIME_KEY_ID) { $env:IPE_RUNTIME_KEY_ID = "runtime" }
if (-not $env:IPE_INTERNAL_KEY_ID) { $env:IPE_INTERNAL_KEY_ID = "control" }
if (-not $env:IPE_INTERNAL_SERVICE_KEY) { $env:IPE_INTERNAL_SERVICE_KEY = "local-service-token" }
if (-not $env:IPE_RUNTIME_PORT) { $env:IPE_RUNTIME_PORT = "19084" }
if (-not $env:IPE_CONTROL_BASE_URL) { $env:IPE_CONTROL_BASE_URL = "http://127.0.0.1:19083" }
if (-not $env:IPE_AGENT_RUNTIME_BASE_URL) {
    $env:IPE_AGENT_RUNTIME_BASE_URL = "http://127.0.0.1:" + $env:IPE_RUNTIME_PORT
}
if (-not $env:IPE_OPS_BASE_URL) { $env:IPE_OPS_BASE_URL = "http://127.0.0.1:19082" }

$registryEnabled = ([string]$env:IPE_AI_REGISTRY_ENABLED).ToLowerInvariant() -eq "true"
$bindAddress = if ($registryEnabled) { "0.0.0.0" } else { "127.0.0.1" }
if ($registryEnabled -and -not $env:IPE_AI_MCP_URL -and -not $env:IPE_AI_MCP_GATEWAY_BASE) {
    Write-Warning "平台注册已启用，但 IPE_AI_MCP_URL 与 IPE_AI_MCP_GATEWAY_BASE 均为空；MCP 发布可能无法解析入口。"
}
if (-not $env:IPE_CREDENTIAL_MASTER_KEY -and -not $env:IPE_CREDENTIAL_MASTER_KEY_FILE) {
    Write-Warning "IPE_CREDENTIAL_MASTER_KEY 未设置，Control 将使用兼容派生密钥；已有密文环境请保持原密钥不变。"
}
if (-not $env:IPE_MODEL_API_KEY) {
    Write-Warning "IPE_MODEL_API_KEY 未设置；若凭据表已有模型凭据，bootstrap 仍可继续。"
}

function Test-IpeOpsRegisteredInNacos {
    if (-not $env:IPE_NACOS_SERVER_ADDR) {
        return $null
    }
    $base = [string]$env:IPE_NACOS_SERVER_ADDR
    if ($base -notmatch '^https?://') {
        $base = "http://" + $base
    }
    $namespace = if ($env:IPE_NACOS_NAMESPACE) { $env:IPE_NACOS_NAMESPACE } else { "public" }
    $group = if ($env:IPE_NACOS_GROUP) { $env:IPE_NACOS_GROUP } else { "DEFAULT_GROUP" }
    $rootUri = $base.TrimEnd("/")
    $uri = $rootUri +
        "/nacos/v1/ns/instance/list?serviceName=iot-protocol-expert-ops&groupName=" +
        [uri]::EscapeDataString($group) +
        "&namespaceId=" +
        [uri]::EscapeDataString($namespace) +
        "&healthyOnly=true"
    try {
        $response = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 3
        return (@($response.hosts).Count -gt 0)
    } catch {
    }
    if (-not $env:IPE_NACOS_USERNAME -or -not $env:IPE_NACOS_PASSWORD) {
        return $null
    }
    try {
        $login = Invoke-RestMethod `
            -Uri ($rootUri + "/nacos/v3/auth/user/login") `
            -Method Post `
            -Body @{ username = $env:IPE_NACOS_USERNAME; password = $env:IPE_NACOS_PASSWORD } `
            -ContentType "application/x-www-form-urlencoded" `
            -TimeoutSec 3
        $token = [string]$login.accessToken
        if (-not $token) {
            return $null
        }
        $v3Uri = $rootUri +
            "/nacos/v3/admin/ns/instance/list?serviceName=iot-protocol-expert-ops&groupName=" +
            [uri]::EscapeDataString($group) +
            "&namespaceId=" +
            [uri]::EscapeDataString($namespace) +
            "&healthyOnly=true&accessToken=" +
            [uri]::EscapeDataString($token)
        $response = Invoke-RestMethod -Uri $v3Uri -Method Get -TimeoutSec 3
        return (@($response.data).Count -gt 0)
    } catch {
        return $null
    }
}

# OPS 的 Nacos 注册是 Higress MCP 的前置条件，与 IPE_AI_REGISTRY_ENABLED 无关。
Write-Host "[seed-ai] 前置条件：OPS 19082 必须已启用 Nacos Discovery 并注册 iot-protocol-expert-ops。"
if (([string]$env:IPE_NACOS_DISCOVERY_ENABLED).ToLowerInvariant() -ne "true") {
    Write-Warning "IPE_NACOS_DISCOVERY_ENABLED 不是 true；OPS 若未注册，Higress tools/call 会返回 503。seed 不启动 OPS。"
}
if (-not $env:IPE_OPS_ADVERTISE_IP) {
    Write-Warning "IPE_OPS_ADVERTISE_IP 未设置；OPS 注册的地址可能不可被 Higress 访问。"
}
try {
    $opsHealth = Invoke-RestMethod -Uri "http://127.0.0.1:19082/actuator/health" -Method Get -TimeoutSec 3
    if (([string]$opsHealth.status).ToUpperInvariant() -ne "UP") {
        Write-Warning "OPS 19082 健康状态不是 UP；MCP 工具调用可能失败。"
    }
} catch {
    Write-Warning "无法访问 OPS 19082；请先启动 OPS，否则 MCP 工具调用会失败。"
}
$opsRegistered = Test-IpeOpsRegisteredInNacos
if ($opsRegistered -eq $false) {
    Write-Warning "Nacos 中没有健康的 iot-protocol-expert-ops 实例；Higress tools/call 可能返回 503。请用 IPE_NACOS_DISCOVERY_ENABLED=true 和可达的 IPE_OPS_ADVERTISE_IP 重启 OPS。"
} elseif ($null -eq $opsRegistered) {
    Write-Warning "无法通过 Nacos OpenAPI 确认 iot-protocol-expert-ops 注册状态（可能未配置 Nacos、启用了认证或网络不可达）。"
}

Write-Host ("[seed-ai] registry={0} bootstrap=true" -f $env:IPE_AI_REGISTRY_ENABLED)

if (-not $SkipBuild) {
    Write-Host "[seed-ai] building iot-protocol-expert-agent-runtime and iot-protocol-expert-ai"
    & $mvn -B -pl iot-protocol-expert-agent-runtime,iot-protocol-expert-ai -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败，退出码 $LASTEXITCODE"
    }
}

$runtimeJar = Join-Path $root "iot-protocol-expert-agent-runtime\target\iot-protocol-expert-agent-runtime-1.0.0.jar"
$controlJar = Join-Path $root "iot-protocol-expert-ai\target\iot-protocol-expert-ai-1.0.0.jar"
$runtimeJarName = "iot-protocol-expert-agent-runtime-1.0.0.jar"
$controlJarName = "iot-protocol-expert-ai-1.0.0.jar"
foreach ($jar in @($runtimeJar, $controlJar)) {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "缺少构建产物：$jar"
    }
}

$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$runtimePidFile = Join-Path $logDir "runtime.pid"
$controlPidFile = Join-Path $logDir "control.pid"
$legacyControlPidFile = Join-Path $root "run\ai.pid"
$runtimeOut = Join-Path $logDir "runtime.out.log"
$runtimeErr = Join-Path $logDir "runtime.err.log"
$controlOut = Join-Path $logDir "control.out.log"
$controlErr = Join-Path $logDir "control.err.log"

function Assert-IpePortAvailable {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $processIds = @(Get-IpePortPids -Port $Port)
    if ($processIds.Count -eq 0) {
        return
    }
    $details = @()
    foreach ($processId in $processIds) {
        $process = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $processId) -ErrorAction SilentlyContinue
        if ($process -and $process.Name) {
            $details += ("pid={0} name={1}" -f $processId, $process.Name)
        } else {
            $details += ("pid={0}" -f $processId)
        }
    }
    throw ("$Label 端口 $Port 已被占用，拒绝停止非本脚本启动的进程：" + ($details -join "; "))
}

function Start-IpeJar {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Jar,
        [Parameter(Mandatory = $true)][string]$JarName,
        [Parameter(Mandatory = $true)][string]$PidFile,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$BindAddress,
        [Parameter(Mandatory = $true)][string]$OutLog,
        [Parameter(Mandatory = $true)][string]$ErrLog
    )

    $owned = Get-IpeOwnedProcess -PidFile $PidFile -JarName $JarName
    if ($owned) {
        Write-Host ("[seed-ai] reuse {0} pid={1}" -f $Label, $owned.Id)
        return [pscustomobject]@{ Id = $owned.Id; Reused = $true }
    }

    Assert-IpePortAvailable -Port $Port -Label $Label
    $process = Start-Process `
        -FilePath (Join-Path $javaHome "bin\java.exe") `
        -ArgumentList @("-Dfile.encoding=UTF-8", "-jar", $Jar, ("--server.address=" + $BindAddress)) `
        -WorkingDirectory $root `
        -RedirectStandardOutput $OutLog `
        -RedirectStandardError $ErrLog `
        -WindowStyle Hidden `
        -PassThru
    Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding ASCII
    Write-Host ("[seed-ai] start {0} pid={1} port={2}" -f $Label, $process.Id, $Port)
    return [pscustomobject]@{ Id = $process.Id; Reused = $false }
}

$publicationSql = @'
select
    p.id as publication_id,
    p.state as publication_state,
    p.phase as publication_phase,
    p.error_code as error_code,
    p.error_message as error_message,
    p.version as agent_version,
    a.component_key as agent_key,
    r.id as release_id,
    r.state as release_state,
    r.generation as generation,
    r.manifest_digest as manifest_digest,
    p.updated_at::text as updated_at
from ipe_ai.ai_publication p
join ipe_ai.ai_agent_component a on a.id = p.resource_id
left join ipe_ai.ai_agent_release r on r.agent_id = a.id and r.version = p.version
where p.resource_type = 'AGENT'
order by p.created_at desc
limit 1
'@

function Get-IpeLatestAgentPublication {
    $rows = @(Invoke-IpeAiJdbc -Mode query -Sql $publicationSql)
    if ($rows.Count -eq 0) {
        return $null
    }
    return $rows[0]
}

$baseline = $null
try {
    $baseline = Get-IpeLatestAgentPublication
} catch {
    Write-Warning "无法在启动前读取发布基线：$($_.Exception.Message)"
}
$baselineId = if ($baseline) { [string]$baseline.publication_id } else { "" }

if ($Restart) {
    Stop-IpeOwnedProcess -PidFile $controlPidFile -JarName $controlJarName -Label "Control"
    Stop-IpeOwnedProcess -PidFile $legacyControlPidFile -JarName $controlJarName -Label "start-local Control"
    Stop-IpeOwnedProcess -PidFile $runtimePidFile -JarName $runtimeJarName -Label "Runtime"
}

$legacyControl = Get-IpeOwnedProcess -PidFile $legacyControlPidFile -JarName $controlJarName
if ($legacyControl) {
    throw ("Control 已由 start-local.ps1 启动（run\ai.pid pid={0}），不会重复启动；请执行 .\scripts\seed-ai.ps1 -Restart 以由 seed 接管。" -f $legacyControl.Id)
}

# Runtime readiness 要求 Control 可达，因此先以 bootstrap=false 启动 Control，
# 待 Runtime readiness 通过后再重启 Control 触发 bootstrap。
$env:IPE_AI_BOOTSTRAP_ENABLED = "false"
$control = Start-IpeJar `
    -Label "Control" `
    -Jar $controlJar `
    -JarName $controlJarName `
    -PidFile $controlPidFile `
    -Port 19083 `
    -BindAddress $bindAddress `
    -OutLog $controlOut `
    -ErrLog $controlErr

try {
    Wait-IpeHttpUp `
        -Uri "http://127.0.0.1:19083/actuator/health" `
        -TimeoutSeconds $StartupTimeoutSeconds `
        -ProcessId $control.Id | Out-Null
} catch {
    throw ("Control 预启动健康检查失败：{0}；日志：{1}，{2}" -f $_.Exception.Message, $controlOut, $controlErr)
}

$runtime = Start-IpeJar `
    -Label "Runtime" `
    -Jar $runtimeJar `
    -JarName $runtimeJarName `
    -PidFile $runtimePidFile `
    -Port ([int]$env:IPE_RUNTIME_PORT) `
    -BindAddress $bindAddress `
    -OutLog $runtimeOut `
    -ErrLog $runtimeErr

try {
    Wait-IpeHttpUp `
        -Uri ("http://127.0.0.1:{0}/actuator/health/readiness" -f $env:IPE_RUNTIME_PORT) `
        -TimeoutSeconds $StartupTimeoutSeconds `
        -ProcessId $runtime.Id | Out-Null
} catch {
    throw ("Runtime readiness 检查失败（Control 已启动）：{0}；日志：{1}，{2}" -f $_.Exception.Message, $runtimeOut, $runtimeErr)
}

if ($control.Reused) {
    Write-Warning "Control 为复用进程，本次不会重新触发 bootstrap；如需重新引导请使用 -Restart。"
} else {
    Stop-IpeOwnedProcess -PidFile $controlPidFile -JarName $controlJarName -Label "Control"
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (@(Get-IpePortPids -Port 19083).Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if (@(Get-IpePortPids -Port 19083).Count -ne 0) {
        throw "Control 停止后端口 19083 仍被占用，无法以 bootstrap=true 重新启动"
    }
    $env:IPE_AI_BOOTSTRAP_ENABLED = "true"
    $control = Start-IpeJar `
        -Label "Control" `
        -Jar $controlJar `
        -JarName $controlJarName `
        -PidFile $controlPidFile `
        -Port 19083 `
        -BindAddress $bindAddress `
        -OutLog $controlOut `
        -ErrLog $controlErr
    try {
        Wait-IpeHttpUp `
            -Uri "http://127.0.0.1:19083/actuator/health" `
            -TimeoutSeconds $StartupTimeoutSeconds `
            -ProcessId $control.Id | Out-Null
    } catch {
        throw ("Control bootstrap 启动健康检查失败：{0}；日志：{1}，{2}" -f $_.Exception.Message, $controlOut, $controlErr)
    }
}

$controlPortPids = @(Get-IpePortPids -Port 19083)
if ($controlPortPids -notcontains $control.Id) {
    throw ("Control pid={0} 未监听 19083；端口由其他进程占用：{1}" -f $control.Id, ($controlPortPids -join ","))
}
$runtimePortPids = @(Get-IpePortPids -Port ([int]$env:IPE_RUNTIME_PORT))
if ($runtimePortPids -notcontains $runtime.Id) {
    throw ("Runtime pid={0} 未监听 {1}；端口由其他进程占用：{2}" -f $runtime.Id, $env:IPE_RUNTIME_PORT, ($runtimePortPids -join ","))
}

function Test-IpeLogMarker {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }
    try {
        return [bool](Select-String -LiteralPath $Path -Pattern $Pattern -SimpleMatch -Quiet)
    } catch {
        return $false
    }
}

function Protect-IpeText {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return ""
    }
    $text = [string]$Value
    foreach ($secret in @(
            $env:IPE_INTERNAL_SERVICE_KEY,
            $env:IPE_CREDENTIAL_MASTER_KEY,
            $env:IPE_AI_MCP_TOKEN,
            $env:IPE_MODEL_API_KEY,
            $env:IPE_EMBEDDING_API_KEY,
            $env:IPE_OPS_SERVICE_TOKEN,
            $env:IPE_NACOS_PASSWORD,
            $env:IPE_TDENGINE_PASSWORD,
            $env:IPE_OPS_DB_PASSWORD,
            $env:IPE_AI_DB_PASSWORD)) {
        if ($secret) {
            $text = $text.Replace([string]$secret, "***")
        }
    }
    return $text
}

$deadline = (Get-Date).AddSeconds($PublicationTimeoutSeconds)
$latest = $baseline
$lastError = $null
$skipMarkerFound = $false
while ((Get-Date) -lt $deadline) {
    try {
        $latest = Get-IpeLatestAgentPublication
        $lastError = $null
    } catch {
        $lastError = $_.Exception.Message
        $latest = $null
    }
    if (Test-IpeLogMarker -Path $controlOut -Pattern "AI platform bootstrap skipped:") {
        $skipMarkerFound = $true
    }
    if (Test-IpeLogMarker -Path $controlOut -Pattern "AI platform bootstrap stopped:") {
        $skipMarkerFound = $true
    }

    if ($latest) {
        $latestId = [string]$latest.publication_id
        $state = ([string]$latest.publication_state).ToUpperInvariant()
        if ($state -eq "PUBLISHED") {
            break
        }
        if ($state -eq "FAILED" -and ($control.Reused -or $latestId -ne $baselineId)) {
            break
        }
    }
    if ($skipMarkerFound) {
        break
    }
    Start-Sleep -Seconds 3
}

Write-Host ""
Write-Host "在线 Agent 发布摘要"
if ($latest) {
    Write-Host ("  publication : {0}" -f (Protect-IpeText $latest.publication_id))
    Write-Host ("  state       : {0}/{1}" -f (Protect-IpeText $latest.publication_state), (Protect-IpeText $latest.publication_phase))
    Write-Host ("  agent       : {0}@{1}" -f (Protect-IpeText $latest.agent_key), (Protect-IpeText $latest.agent_version))
    Write-Host ("  release     : {0}" -f (Protect-IpeText $latest.release_id))
    Write-Host ("  releaseState: {0}" -f (Protect-IpeText $latest.release_state))
    Write-Host ("  generation  : {0}" -f (Protect-IpeText $latest.generation))
    Write-Host ("  manifest    : {0}" -f (Protect-IpeText $latest.manifest_digest))
    Write-Host ("  updatedAt   : {0}" -f (Protect-IpeText $latest.updated_at))
    if ($latest.error_code -or $latest.error_message) {
        Write-Host ("  error       : {0} {1}" -f (Protect-IpeText $latest.error_code), (Protect-IpeText $latest.error_message))
    }
} else {
    Write-Host "  未读取到 Agent 发布记录"
}
if ($control.Reused) {
    Write-Host "  注意：Control 为复用进程，本次未重新触发 bootstrap；如需重新引导请使用 -Restart。"
} elseif ($latest -and $baseline -and ([string]$latest.publication_id) -eq $baselineId) {
    Write-Host "  注意：本次未产生新的 Agent 发布记录，展示的是已有发布；如需重建请先运行 reset-ai.ps1 再执行 -Restart。"
} elseif ($skipMarkerFound) {
    Write-Host "  注意：Control 日志显示 bootstrap 已跳过或中止，请检查模型凭据与 IPE_AI_REGISTRY_ENABLED。"
} elseif ($lastError) {
    Write-Host ("  注意：数据库摘要读取失败：{0}" -f (Protect-IpeText $lastError))
}
Write-Host ""
Write-Host ("health      : Control=UP, Runtime=UP；日志：{0}，{1}" -f $controlOut, $runtimeOut)

if (-not $latest) {
    exit 1
}
if (([string]$latest.publication_state).ToUpperInvariant() -eq "FAILED") {
    exit 1
}
exit 0
