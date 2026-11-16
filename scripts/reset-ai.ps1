param(
    [switch]$Confirm,
    [switch]$WhatIf,
    [switch]$StopServices
)

$ErrorActionPreference = "Stop"

if (-not $Confirm) {
    Write-Host "拒绝执行：reset-ai.ps1 会删除 ipe_ai 中在线 Agent 的运行、发布和配置数据。"
    Write-Host "确认已停止 Control/Runtime，并已备份需要保留的数据后，显式传入："
    Write-Host "  .\scripts\reset-ai.ps1 -Confirm"
    Write-Host "仅查看 SQL："
    Write-Host "  .\scripts\reset-ai.ps1 -Confirm -WhatIf"
    Write-Host "如进程由 seed-ai.ps1 启动，可同时停止："
    Write-Host "  .\scripts\reset-ai.ps1 -Confirm -StopServices"
    exit 2
}

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot "lib\ipe-ai-common.ps1")

$envFile = Join-Path $root ".env.local"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "缺少 .env.local，无法确定目标数据库"
}
$processEnvironmentBeforeLoad = [string]$env:IPE_ENVIRONMENT
$processDbBeforeLoad = ([string]$env:IPE_AI_DB_URL + "`n" + [string]$env:IPE_OPS_DB_URL)
Import-IpeDotEnv -Path $envFile

$dbUrl = [string]$env:IPE_AI_DB_URL
if ([string]::IsNullOrWhiteSpace($dbUrl)) {
    $dbUrl = [string]$env:IPE_OPS_DB_URL
}
if ([string]::IsNullOrWhiteSpace($dbUrl)) {
    throw "未配置 IPE_AI_DB_URL 或 IPE_OPS_DB_URL"
}
if (-not $dbUrl.StartsWith("jdbc:postgresql:", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "reset-ai.ps1 只支持 PostgreSQL JDBC URL"
}

# 宁可误拒绝，也不允许在生产或疑似生产环境执行清理；同时检查加载前后环境。
$guardText = (
    $processEnvironmentBeforeLoad + "`n" +
    $processDbBeforeLoad + "`n" +
    [string]$env:IPE_ENVIRONMENT + "`n" +
    $dbUrl
).ToLowerInvariant()
if ($guardText.Contains("prod")) {
    throw "拒绝执行：IPE_ENVIRONMENT 或数据库 URL 含 prod/production，疑似生产环境"
}

if ($StopServices -and -not $WhatIf) {
    $controlJarName = "iot-protocol-expert-ai-1.0.0.jar"
    $runtimeJarName = "iot-protocol-expert-agent-runtime-1.0.0.jar"
    Stop-IpeOwnedProcess -PidFile (Join-Path $root "logs\control.pid") -JarName $controlJarName -Label "Control"
    Stop-IpeOwnedProcess -PidFile (Join-Path $root "run\ai.pid") -JarName $controlJarName -Label "start-local Control"
    Stop-IpeOwnedProcess -PidFile (Join-Path $root "logs\runtime.pid") -JarName $runtimeJarName -Label "Runtime"
}

if (-not $WhatIf) {
    $controlJarName = "iot-protocol-expert-ai-1.0.0.jar"
    $runtimeJarName = "iot-protocol-expert-agent-runtime-1.0.0.jar"
    $controlPidFile = Join-Path $root "logs\control.pid"
    $runtimePidFile = Join-Path $root "logs\runtime.pid"

    $control = Get-IpeOwnedProcess -PidFile $controlPidFile -JarName $controlJarName
    $runtime = Get-IpeOwnedProcess -PidFile $runtimePidFile -JarName $runtimeJarName
    if ($control -or $runtime) {
        $running = @()
        if ($control) { $running += ("Control pid=" + $control.Id) }
        if ($runtime) { $running += ("Runtime pid=" + $runtime.Id) }
        throw ("Control/Runtime 仍在运行：" + ($running -join ", ") + "；请先停止两个进程再执行 reset")
    }

    function Find-IpeProjectProcessOnPort {
        param(
            [Parameter(Mandatory = $true)][int]$Port,
            [Parameter(Mandatory = $true)][string]$JarName
        )

        foreach ($processId in @(Get-IpePortPids -Port $Port)) {
            $process = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $processId) -ErrorAction SilentlyContinue
            if ($process -and $process.CommandLine -and $process.CommandLine.Contains($JarName)) {
                return $process
            }
        }
        return $null
    }

    $runtimePort = if ($env:IPE_RUNTIME_PORT) { [int]$env:IPE_RUNTIME_PORT } else { 19084 }
    $controlOnPort = Find-IpeProjectProcessOnPort -Port 19083 -JarName $controlJarName
    $runtimeOnPort = Find-IpeProjectProcessOnPort -Port $runtimePort -JarName $runtimeJarName
    if ($controlOnPort -or $runtimeOnPort) {
        $running = @()
        if ($controlOnPort) { $running += ("Control pid=" + $controlOnPort.ProcessId) }
        if ($runtimeOnPort) { $running += ("Runtime pid=" + $runtimeOnPort.ProcessId) }
        throw ("Control/Runtime 端口仍被本项目进程占用：" + ($running -join ", ") + "；请先停止后再执行 reset")
    }
}

# 只清理在线 Agent 平台的运行、发布和配置数据。
# 旧版 ai_agent_definition / ai_agent_binding 仅保留历史审计，不在本清单中。
# 所有语句通过一个 JDBC 事务执行；任何一张表不存在都会整体回滚。
$tables = @(
    "ai_run_event",
    "ai_tool_call",
    "ai_checkpoint",
    "ai_context_summary",
    "ai_run",
    "ai_message",
    "ai_conversation",
    "ai_outbox",
    "ai_registry_projection",
    "ai_publication",
    "ai_agent_skill_binding",
    "ai_agent_mcp_binding",
    "ai_agent_release",
    "ai_agent_component",
    "ai_skill_draft_file",
    "ai_skill_release",
    "ai_skill_component",
    "ai_mcp_test_report",
    "ai_mcp_release",
    "ai_mcp_component",
    "ai_model_profile",
    "ai_credential_secret",
    "ai_credential",
    "ai_internal_nonce"
)

$sqlLines = foreach ($table in $tables) {
    "delete from ipe_ai.$table;"
}
$sql = $sqlLines -join "`n"

if ($WhatIf) {
    Write-Host "以下 SQL 将在单个事务中执行（未连接数据库）："
    Write-Host $sql
    Write-Host ""
    Write-Host "保留：Flyway 历史、ipe_ai schema、ai_permission_policy、知识库表、ai_audit_log、旧版 ai_agent_definition/ai_agent_binding。"
    exit 0
}

Write-Host "开始清理 ipe_ai 在线 Agent 数据；表清单："
$tables | ForEach-Object { Write-Host ("  - " + $_) }

$result = Invoke-IpeAiJdbc -Mode update -Sql $sql
$affected = @($result.Affected)
for ($index = 0; $index -lt $tables.Count; $index++) {
    $count = 0
    if ($index -lt $affected.Count) {
        $count = $affected[$index].Count
    }
    Write-Host ("  {0,-28} {1}" -f $tables[$index], $count)
}
Write-Host ("总计删除 {0} 行" -f $result.Total)
Write-Host "已保留：Flyway 历史、ipe_ai schema、ai_permission_policy、知识库表、ai_audit_log、旧版 ai_agent_definition/ai_agent_binding。"
Write-Host "下一步可执行：.\scripts\seed-ai.ps1 -Restart"
