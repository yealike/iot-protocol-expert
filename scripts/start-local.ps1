param(
    [switch]$EnableOtlp,
    [string]$OtlpEndpoint,
    [string]$JavaHome = $env:IPE_JAVA_HOME,
    [long]$DeviceId = 0
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")

if ($JavaHome) { $env:IPE_JAVA_HOME = $JavaHome }
$root = Initialize-PepLocalEnvironment
if (-not $OtlpEndpoint) { $OtlpEndpoint = $env:IPE_OTLP_ENDPOINT }

if ($EnableOtlp -or $env:IPE_OTLP_ENABLED -eq "true") {
    if (-not $OtlpEndpoint) { $OtlpEndpoint = "http://127.0.0.1:4318" }
    $OtlpEndpoint = $OtlpEndpoint.TrimEnd("/")
    $env:IPE_OTLP_ENABLED = "true"
    $env:IPE_OTLP_ENDPOINT = $OtlpEndpoint
} else {
    $env:IPE_OTLP_ENABLED = "false"
}
if ($DeviceId -gt 0) { $env:IPE_SIMULATOR_DEVICE_ID = "$DeviceId" }
if (-not $env:IPE_PROTOCOL_DOCUMENT_DIR) {
    $env:IPE_PROTOCOL_DOCUMENT_DIR = Join-Path $root "data\protocol-documents"
}

$otelArgs = @()
if ($env:IPE_OTLP_ENABLED -eq "true") {
    $otelArgs = @(
        "--management.tracing.export.otlp.enabled=true",
        "--management.logging.export.otlp.enabled=true",
        "--management.otlp.metrics.export.enabled=true",
        "--management.opentelemetry.tracing.export.otlp.endpoint=$OtlpEndpoint/v1/traces",
        "--management.opentelemetry.logging.export.otlp.endpoint=$OtlpEndpoint/v1/logs",
        "--management.otlp.metrics.export.url=$OtlpEndpoint/v1/metrics"
    )
}

$bindAddress = if ($env:IPE_NACOS_DISCOVERY_ENABLED -eq "true" -or $env:IPE_AI_REGISTRY_ENABLED -eq "true") {
    "0.0.0.0"
} else {
    "127.0.0.1"
}

foreach ($service in Get-PepServiceDefinitions) {
    $jar = Get-PepServiceJar -Root $root -Module $service.Module -Jar $service.Jar
    Start-PepJar `
        -Root $root `
        -Name $service.Name `
        -Jar $jar `
        -Port $service.Port `
        -HealthPath $service.HealthPath `
        -BindAddress $bindAddress `
        -TimeoutSeconds 180 `
        -ExtraArgs $otelArgs | Out-Null
}

Write-Host ""
Write-Host "服务已全部就绪："
Write-Host "  control         http://127.0.0.1:$($env:PEP_CONTROL_PORT)  (登录 admin/admin123)"
Write-Host "  model-gateway   http://127.0.0.1:$($env:PEP_MODEL_GATEWAY_PORT)"
Write-Host "  agent-runtime   http://127.0.0.1:$($env:IPE_RUNTIME_PORT)"
Write-Host "  device-gateway  tcp 8768 / ocpp 8769 / http 19080"
Write-Host "  simulator       http://127.0.0.1:19081"
Write-Host "前端：cd pep-console; npm run dev  (http://localhost:5173)"
Write-Host "停止：scripts/stop-local.ps1"
