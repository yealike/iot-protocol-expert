<#
.SYNOPSIS
    由仓库根目录 .env.local 生成服务器部署用 deploy\.env。

.DESCRIPTION
    - 把 -LocalAddresses 指定的本机 / Tailscale 地址替换为服务器内网地址，避免绕行 Tailscale。
    - IPE_TDENGINE_URL 例外：当前服务器 TDengine 只监听 Tailscale 地址，替换后容器会连接失败。
    - 覆盖容器内服务地址（Docker 服务名）和 Langfuse / Trace 内容采集开关。
    - Langfuse 密钥从 deploy\.env.langfuse 读取，不写入仓库跟踪文件。

.EXAMPLE
    .\deploy\build-env.ps1 -ServerAddress <服务器内网地址> -LocalAddresses "<Tailscale 地址>"
    .\deploy\build-env.ps1 -ServerAddress <服务器内网地址> -LangfuseServerUrl http://<服务器内网地址>:3005 -LocalAddresses "<Tailscale 地址>","<对端 Tailscale 地址>"
#>
param(
    [string]$ServerAddress = "SERVER_IP",
    [string]$LangfuseServerUrl = "",
    [string]$Source = "",
    [string[]]$LocalAddresses = @()
)

$ErrorActionPreference = "Stop"
$deployDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $deployDir
if (-not $Source) { $Source = Join-Path $repoRoot ".env.local" }
if (-not (Test-Path $Source)) { throw "缺少源配置：$Source" }
if (-not $ServerAddress -or $ServerAddress -eq "SERVER_IP") {
    throw "请显式传入 -ServerAddress <服务器内网地址>"
}
if (-not $LocalAddresses -or $LocalAddresses.Count -eq 0) {
    throw "请显式传入 -LocalAddresses <源配置中的本机/Tailscale 地址>"
}

$values = [ordered]@{}
foreach ($line in Get-Content -LiteralPath $Source) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
    $index = $trimmed.IndexOf("=")
    if ($index -le 0) { continue }
    $key = $trimmed.Substring(0, $index).Trim()
    $value = $trimmed.Substring($index + 1).Trim()
    $values[$key] = $value
}

foreach ($key in @($values.Keys)) {
    if ($key -eq "IPE_TDENGINE_URL") { continue }
    $value = [string]$values[$key]
    foreach ($localAddress in $LocalAddresses) {
        $value = $value.Replace($localAddress, $ServerAddress)
    }
    $values[$key] = $value
}

$overrides = [ordered]@{
    HOST_BIND_IP                         = $ServerAddress
    IPE_ENVIRONMENT                     = "server"
    IPE_OTLP_ENDPOINT                   = "http://${ServerAddress}:4318"
    IPE_OPS_BASE_URL                    = "http://ipe-ops:19082"
    IPE_CONTROL_BASE_URL                = "http://ipe-control:19083"
    IPE_SIMULATOR_BASE_URL              = "http://ipe-simulator:19081"
    IPE_PROTOCOL_BASE_URL               = "http://ipe-device-gateway:19080"
    IPE_AGENT_RUNTIME_BASE_URL          = "http://ipe-runtime:19084"
    PEP_MODEL_GATEWAY_BASE_URL          = "http://ipe-model-gateway:19085"
    IPE_LOG_DIR                         = "/app/logs"
    IPE_PROTOCOL_DOCUMENT_DIR           = "/app/data/protocol-documents"
    IPE_OTLP_ENABLED                    = "true"
    IPE_NACOS_DISCOVERY_ENABLED         = "false"
    IPE_AI_REGISTRY_ENABLED             = "false"
    IPE_LANGFUSE_ENABLED                = "true"
    IPE_LANGFUSE_SERVER_URL             = if ($LangfuseServerUrl) { $LangfuseServerUrl } else { "http://${ServerAddress}:3005" }
    IPE_RUNTIME_OTEL_CONTENT_ENABLED    = "true"
    IPE_RUNTIME_OTEL_MAX_CONTENT_CHARS  = "0"
    IPE_AI_RUN_MAX_ITERS                = "12"
    IPE_AI_RUN_MAX_TOOL_CALLS           = "30"
    IPE_AI_RUN_TIMEOUT_MS               = "600000"
    IPE_AI_RUN_MAX_CONTEXT_MESSAGES     = "60"
    IPE_AI_TOP_K                        = "10"
}
foreach ($entry in $overrides.GetEnumerator()) { $values[$entry.Key] = $entry.Value }
$values.Remove("IPE_JAVA_HOME")

if (-not $values["IPE_TDENGINE_URL"]) {
    throw "源配置缺少 IPE_TDENGINE_URL；服务器 TDengine 地址必须显式配置"
}

if (-not $values["IPE_LANGFUSE_PUBLIC_KEY"] -or -not $values["IPE_LANGFUSE_SECRET_KEY"]) {
    $keyFile = Join-Path $deployDir ".env.langfuse"
    if (Test-Path $keyFile) {
        foreach ($line in Get-Content -LiteralPath $keyFile) {
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
            $index = $trimmed.IndexOf("=")
            if ($index -le 0) { continue }
            $values[$trimmed.Substring(0, $index).Trim()] = $trimmed.Substring($index + 1).Trim()
        }
    }
}

$lines = @("# 服务器部署配置，由 build-env.ps1 生成；含密钥，勿提交到仓库。")
foreach ($entry in $values.GetEnumerator()) {
    $lines += "$($entry.Key)=$($entry.Value)"
}
$placeholderLines = $lines | Where-Object { $_ -match "SERVER_IP|SERVER_USER|LOCAL_IP|PEER_IP" }
if ($placeholderLines) {
    throw "生成的 .env 仍包含占位符，请检查源配置：`n$($placeholderLines -join "`n")"
}
$outFile = Join-Path $deployDir ".env"
[IO.File]::WriteAllText($outFile, ($lines -join "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
Write-Host "已生成 $outFile（$($values.Count) 项）" -ForegroundColor Green
Write-Host "  工作台   http://${ServerAddress}:8095"
Write-Host "  Langfuse http://${ServerAddress}:3005"
