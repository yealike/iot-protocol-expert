<#
.SYNOPSIS
    一键构建并部署 iot-protocol-expert 到服务器（Docker Compose）。

.EXAMPLE
    .\deploy\deploy.ps1                 # 全量构建 + 部署
    .\deploy\deploy.ps1 -SkipBuild      # 只重新打包上传现有 JAR
    .\deploy\deploy.ps1 -SkipWeb        # 不改前端
#>
param(
    [string]$ServerHost = "SERVER_IP",
    [string]$ServerUser = "SERVER_USER",
    [string]$RemoteDir = "/home/SERVER_USER/iot-protocol-expert/deploy",
    [switch]$SkipBuild,
    [switch]$SkipWeb,
    [int]$HealthTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
$deployDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $deployDir
$mvn = "D:\develop\apache-maven-3.8.8\bin\mvn.cmd"
$env:JAVA_HOME = "D:\develop\jdk\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;D:\develop\apache-maven-3.8.8\bin;$env:PATH"
$target = "$ServerUser@$ServerHost"

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

if (-not (Test-Path "$deployDir\.env")) {
    throw "缺少 deploy\.env：请先运行 deploy\build-env.ps1 生成（或参照 .env.deploy.example 手工创建）"
}

if (-not $SkipBuild) {
    Write-Step "Maven 打包全部模块"
    & $mvn -B -f "$repoRoot\pom.xml" -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败（exit $LASTEXITCODE）" }
}

if (-not $SkipWeb) {
    Write-Step "构建前端"
    Push-Location "$repoRoot\iot-protocol-expert-web"
    try {
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "前端构建失败（exit $LASTEXITCODE）" }
    } finally { Pop-Location }
}

Write-Step "准备上传目录"
$jars = [ordered]@{
    "iot-protocol-expert-ops"              = "iot-protocol-expert-ops-1.0.0.jar"
    "iot-protocol-expert-agent-control"    = "iot-protocol-expert-agent-control-1.0.0.jar"
    "iot-protocol-expert-model-gateway"    = "iot-protocol-expert-model-gateway-1.0.0.jar"
    "iot-protocol-expert-agent-runtime"    = "iot-protocol-expert-agent-runtime-1.0.0.jar"
    "iot-protocol-expert-protocol-gateway" = "iot-protocol-expert-protocol-gateway-1.0.0.jar"
    "iot-protocol-expert-simulator"        = "iot-protocol-expert-simulator-1.0.0.jar"
}
New-Item -ItemType Directory -Force "$deployDir\jars" | Out-Null
foreach ($entry in $jars.GetEnumerator()) {
    $src = "$repoRoot\$($entry.Key)\target\$($entry.Value)"
    if (-not (Test-Path $src)) { throw "缺少 $src，请先构建" }
    Copy-Item $src "$deployDir\jars\$($entry.Value)" -Force
}
Remove-Item "$deployDir\dist" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item "$repoRoot\iot-protocol-expert-web\dist" "$deployDir\dist" -Recurse -Force

Write-Step "同步到 $target`:$RemoteDir"
ssh -o BatchMode=yes $target "mkdir -p $RemoteDir/jars $RemoteDir/docker $RemoteDir/web $RemoteDir/data/logs $RemoteDir/data/protocol-documents"
scp -q "$deployDir\docker-compose.yml" "$deployDir\.env" "${target}:$RemoteDir/"
scp -q "$deployDir\docker\Dockerfile.service" "${target}:$RemoteDir/docker/"
scp -q "$deployDir\web\Dockerfile" "$deployDir\web\nginx.conf" "${target}:$RemoteDir/web/"
Get-ChildItem "$deployDir\jars" -Filter *.jar | ForEach-Object {
    scp -q $_.FullName "${target}:$RemoteDir/jars/"
    if ($LASTEXITCODE -ne 0) { throw "上传 $($_.Name) 失败" }
}
ssh -o BatchMode=yes $target "mkdir -p $RemoteDir/web/dist"
scp -qr "$deployDir\dist" "${target}:$RemoteDir/web/"
if ($LASTEXITCODE -ne 0) { throw "上传前端静态文件失败" }

if ($HealthTimeoutSeconds -le 0) { exit 0 }

Write-Step "远程构建并启动"
ssh -o BatchMode=yes $target "set -o pipefail; cd $RemoteDir && docker compose up -d --build --remove-orphans 2>&1 | tail -25 && docker compose restart ipe-web 2>&1 | tail -3"
if ($LASTEXITCODE -ne 0) { throw "远程部署失败（exit $LASTEXITCODE）" }

Write-Step "等待服务健康"
$checks = [ordered]@{
    "ops"             = "http://${ServerHost}:19082/actuator/health"
    "control"         = "http://127.0.0.1:19083/actuator/health"
    "model-gateway"   = "http://127.0.0.1:19085/actuator/health"
    "runtime"         = "http://127.0.0.1:19084/actuator/health/readiness"
    "device-gateway"  = "http://127.0.0.1:19080/actuator/health"
    "simulator"       = "http://127.0.0.1:19081/actuator/health"
    "web"             = "http://127.0.0.1:8095/"
}
$deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
do {
    $pending = @()
    foreach ($name in $checks.Keys) {
        $code = ssh -o BatchMode=yes $target "curl -s -o /dev/null -w '%{http_code}' --max-time 3 $($checks[$name])"
        if ($code -ne "200") { $pending += $name }
    }
    if ($pending.Count -eq 0) { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)

if ($pending.Count -gt 0) {
    Write-Warning "以下服务未在超时内就绪：$($pending -join ', ')"
    ssh -o BatchMode=yes $target "cd $RemoteDir && docker compose ps"
    exit 1
}

Write-Host "`n全部服务已就绪：" -ForegroundColor Green
Write-Host "  工作台   http://${ServerHost}:8095"
Write-Host "  Langfuse http://${ServerHost}:3005"
