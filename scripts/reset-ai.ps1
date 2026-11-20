param(
    [switch]$Confirm,
    [switch]$WhatIf,
    [switch]$StopServices
)

$ErrorActionPreference = "Stop"
if (-not $Confirm) {
    Write-Host "拒绝执行：reset-ai.ps1 会删除 pep_agent 中在线 Agent 的运行、发布和配置数据。"
    Write-Host "确认已停止 Control/Runtime/Gateway 并备份后，显式传入："
    Write-Host "  .\scripts\reset-ai.ps1 -Confirm"
    Write-Host "仅查看 SQL：.\scripts\reset-ai.ps1 -Confirm -WhatIf"
    Write-Host "同时停止本地服务：.\scripts\reset-ai.ps1 -Confirm -StopServices"
    exit 2
}

. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")
$root = Initialize-PepLocalEnvironment -Quiet

$dbUrl = [string]$env:PEP_CONTROL_DB_URL
if ([string]::IsNullOrWhiteSpace($dbUrl)) { $dbUrl = [string]$env:IPE_OPS_DB_URL }
if ([string]::IsNullOrWhiteSpace($dbUrl)) {
    throw "未配置 PEP_CONTROL_DB_URL 或 IPE_OPS_DB_URL"
}
if (-not $env:IPE_OPS_DB_URL) { $env:IPE_OPS_DB_URL = $dbUrl }
$guard = ("$env:IPE_ENVIRONMENT`n$dbUrl").ToLowerInvariant()
if ($guard.Contains("prod")) {
    throw "拒绝执行：环境或数据库 URL 含 prod/production"
}

if ($StopServices -and -not $WhatIf) {
    foreach ($service in Get-PepServiceDefinitions) {
        Stop-PepJar -Root $root -Name $service.Name -JarName $service.Jar
    }
}

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
$sql = ($tables | ForEach-Object { "delete from pep_agent.$_;" }) -join "`n"

if ($WhatIf) {
    Write-Host $sql
    Write-Host ""
    Write-Host "保留 pep_agent 历史调查记录、知识库、ai_permission_policy、ai_audit_log 和 Flyway 历史。"
    exit 0
}

if ($dbUrl -notmatch "postgresql") {
    throw "reset-ai.ps1 只支持 PostgreSQL JDBC URL"
}
$result = Invoke-IpeAiJdbc -Mode update -Sql $sql
Write-Host ("已清理 pep_agent 在线 Agent 数据；影响语句数：{0}" -f @($result.Affected).Count)
