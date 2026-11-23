<#
.SYNOPSIS
    一键构建并部署 iot-protocol-expert 到服务器（Docker Compose）。

.EXAMPLE
    .\deploy\deploy.ps1                 # 全量构建 + 部署
    .\deploy\deploy.ps1 -SkipBuild      # 只重新打包上传现有 JAR
    .\deploy\deploy.ps1 -SkipWeb        # 不改前端
    $env:IPE_SMOKE_USER="admin"; $env:IPE_SMOKE_PASSWORD="***"; .\deploy\deploy.ps1
#>
param(
    [string]$ServerHost = "SERVER_IP",
    [string]$ServerUser = "SERVER_USER",
    [string]$RemoteDir = "/home/SERVER_USER/iot-protocol-expert/deploy",
    [switch]$SkipBuild,
    [switch]$SkipWeb,
    [int]$HealthTimeoutSeconds = 300,
    [string]$SmokeUser = $env:IPE_SMOKE_USER,
    [string]$SmokePassword = $env:IPE_SMOKE_PASSWORD
)

$ErrorActionPreference = "Stop"
$deployDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $deployDir
$mvn = "D:\develop\apache-maven-3.8.8\bin\mvn.cmd"
$env:JAVA_HOME = "D:\develop\jdk\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;D:\develop\apache-maven-3.8.8\bin;$env:PATH"
$target = "$ServerUser@$ServerHost"

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Assert-LastExit([string]$action) {
    if ($LASTEXITCODE -ne 0) { throw "$action 失败（exit $LASTEXITCODE）" }
}
function Invoke-Remote([string]$command, [string]$action) {
    & ssh -o BatchMode=yes $target $command
    Assert-LastExit $action
}
function Send-File([string]$local, [string]$remote, [switch]$Recursive) {
    if ($Recursive) {
        & scp -qr $local "${target}:$remote"
    } else {
        & scp -q $local "${target}:$remote"
    }
    Assert-LastExit "上传 $local"
}

if ($ServerHost -eq "SERVER_IP" -or $ServerUser -eq "SERVER_USER" -or $RemoteDir -match "SERVER_USER") {
    throw "服务器参数仍是占位符：请显式传入 -ServerHost <内网地址> -ServerUser <账号> -RemoteDir <远端部署目录>"
}

if (-not (Test-Path "$deployDir\.env")) {
    throw "缺少 deploy\.env：请先运行 deploy\build-env.ps1 生成（或参照 .env.deploy.example 手工创建）"
}

if (-not $SkipBuild) {
    Write-Step "Maven 全量测试并打包全部模块"
    # 必须 clean：已删除的 MyBatis 映射等资源会残留在 target/classes，被打进 JAR 导致服务启动失败
    & $mvn -B -f "$repoRoot\pom.xml" -Pcoding-guidelines clean verify
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
Invoke-Remote "mkdir -p $RemoteDir/jars $RemoteDir/docker $RemoteDir/web $RemoteDir/data/logs $RemoteDir/data/protocol-documents" "创建远端目录"
Send-File "$deployDir\docker-compose.yml" "$RemoteDir/"
Send-File "$deployDir\.env" "$RemoteDir/"
Send-File "$deployDir\docker\Dockerfile.service" "$RemoteDir/docker/"
Send-File "$deployDir\web\Dockerfile" "$RemoteDir/web/"
Send-File "$deployDir\web\nginx.conf" "$RemoteDir/web/"
Get-ChildItem "$deployDir\jars" -Filter *.jar | ForEach-Object {
    Send-File $_.FullName "$RemoteDir/jars/"
}
Invoke-Remote "mkdir -p $RemoteDir/web/dist" "创建远端前端目录"
Send-File "$deployDir\dist" "$RemoteDir/web/" -Recursive

if ($HealthTimeoutSeconds -le 0) { exit 0 }

Write-Step "备份当前镜像标签（失败可回滚）"
$previousImages = @()
$imagesJson = (& ssh -o BatchMode=yes $target "cd $RemoteDir && docker compose images --format json") -join "`n"
if ($LASTEXITCODE -ne 0) { throw "读取当前镜像失败（exit $LASTEXITCODE）" }
if ($imagesJson.Trim()) {
    foreach ($image in ($imagesJson | ConvertFrom-Json)) {
        if (-not $image.Repository -or -not $image.Tag) { continue }
        $reference = "$($image.Repository):$($image.Tag)"
        Invoke-Remote "docker tag '$reference' '$($image.Repository):deploy-previous'" "备份镜像 $reference"
        $previousImages += $reference
    }
}

Write-Step "远程构建并启动"
$deployStartedAt = Get-Date
try {
    Invoke-Remote "set -o pipefail; cd $RemoteDir && (docker network inspect ipe-mcp-backend >/dev/null 2>&1 || docker network create ipe-mcp-backend >/dev/null) && docker compose up -d --build --remove-orphans 2>&1 | tail -25 && docker compose restart ipe-web 2>&1 | tail -3" "远程部署"

    Write-Step "等待容器内健康检查"
    $healthScript = @'
cd "$REMOTE_DIR" || exit 1
failed=""
check() { docker compose exec -T "$1" sh -c "$2" >/dev/null 2>&1 || failed="$failed $1"; }
check ipe-ops "curl -fsS -m 3 http://127.0.0.1:19082/actuator/health"
check ipe-control "curl -fsS -m 3 http://127.0.0.1:19083/actuator/health"
check ipe-model-gateway "curl -fsS -m 3 http://127.0.0.1:19085/actuator/health"
check ipe-runtime "curl -fsS -m 3 http://127.0.0.1:19084/actuator/health/readiness"
check ipe-device-gateway "curl -fsS -m 3 http://127.0.0.1:19080/actuator/health"
check ipe-simulator "curl -fsS -m 3 http://127.0.0.1:19081/actuator/health"
check ipe-web "wget -qO- http://127.0.0.1/ >/dev/null"
if [ -n "$failed" ]; then echo "UNHEALTHY:$failed"; exit 1; fi
'@
    $healthScript = $healthScript.Replace('$REMOTE_DIR', $RemoteDir)
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    do {
        & ssh -o BatchMode=yes $target $healthScript
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)

    if ($LASTEXITCODE -ne 0) { throw "服务未在 ${HealthTimeoutSeconds}s 内全部健康" }

    Write-Step "校验 TDengine 连通性"
    $tdengineScript = @'
cd "$REMOTE_DIR" || exit 1
docker compose exec -T ipe-ops sh -c 'url="${IPE_TDENGINE_URL#jdbc:TAOS-WS://}"; url="${url#jdbc:TAOS-RS://}"; curl -fsS -m 5 -u "$IPE_TDENGINE_USER:$IPE_TDENGINE_PASSWORD" -d "SELECT SERVER_VERSION()" "http://$url/rest/sql"' | grep -q '"code":0'
'@
    $tdengineScript = $tdengineScript.Replace('$REMOTE_DIR', $RemoteDir)
    Invoke-Remote $tdengineScript "TDengine 连通性校验"

    if ($SmokeUser -and $SmokePassword) {
        Write-Step "业务冒烟：登录、订单、遥测、AI 管理"
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $loginBody = "username=$([uri]::EscapeDataString($SmokeUser))&password=$([uri]::EscapeDataString($SmokePassword))"
        $login = Invoke-RestMethod -Uri "http://${ServerHost}:8095/login" -Method Post -Body $loginBody `
            -ContentType 'application/x-www-form-urlencoded' -WebSession $session -TimeoutSec 30
        if (-not $login.authenticated) { throw "登录冒烟失败：响应未返回 authenticated" }

        $orders = Invoke-RestMethod -Uri "http://${ServerHost}:8095/api/ops/orders/page?page=0&size=1" `
            -WebSession $session -TimeoutSec 30
        $orderNo = $orders.content[0].orderNo
        if (-not $orderNo) { throw "订单冒烟失败：没有可查询的订单" }

        $telemetry = Invoke-RestMethod -Uri "http://${ServerHost}:8095/api/ops/orders/$orderNo/telemetry" `
            -WebSession $session -TimeoutSec 30
        if ($null -eq $telemetry.points) { throw "遥测冒烟失败：响应缺少 points" }

        $agentsResponse = Invoke-RestMethod -Uri "http://${ServerHost}:8095/api/ai/v1/agents" `
            -WebSession $session -TimeoutSec 30
        if ($agentsResponse.code -ne 'OK' -or $null -eq $agentsResponse.data) {
            throw "AI 管理冒烟失败：响应信封异常（code=$($agentsResponse.code)）"
        }
        $agents = @($agentsResponse.data)
        if ($agents.Count -eq 0) { throw "AI 管理冒烟失败：Agent 列表为空" }
        Write-Host "  登录通过；订单 $orderNo 遥测 $($telemetry.points.Count) 点；AI Agent $($agents.Count) 个" -ForegroundColor Green
    } else {
        Write-Warning "未提供 IPE_SMOKE_USER / IPE_SMOKE_PASSWORD，已跳过登录、订单遥测与 AI 管理冒烟"
    }
} catch {
    Write-Warning $_.Exception.Message
    if ($previousImages.Count -gt 0) {
        Write-Warning "开始回滚到部署前镜像"
        foreach ($reference in $previousImages) {
            $previous = "$($reference.Substring(0, $reference.LastIndexOf(':'))):deploy-previous"
            & ssh -o BatchMode=yes $target "docker tag '$previous' '$reference'"
        }
        & ssh -o BatchMode=yes $target "cd $RemoteDir && docker compose up -d --no-build --remove-orphans"
        Write-Warning "已尝试回滚，请检查 docker compose ps 与日志"
    }
    exit 1
}

Write-Step "检查容器状态与重启次数"
$stateScript = @'
cd "$REMOTE_DIR" || exit 1
ids=$(docker compose ps -q) || exit 1
[ -n "$ids" ] || { echo "没有运行中的容器"; exit 1; }
docker inspect -f '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}' $ids
bad=$(docker inspect -f '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}' $ids | awk '$2!="running" || ($3!="healthy" && $3!="none") || $4!=0')
if [ -n "$bad" ]; then echo "状态异常:"; echo "$bad"; exit 1; fi
'@
    $stateScript = $stateScript.Replace('$REMOTE_DIR', $RemoteDir)
    Invoke-Remote $stateScript "容器状态检查"

Write-Step "检查启动日志"
$logSince = [int]((Get-Date) - $deployStartedAt).TotalSeconds + 5
$logScript = @'
cd "$REMOTE_DIR" || exit 1
logs=$(docker compose logs --since ${SINCE}s 2>&1)
if printf '%s\n' "$logs" | grep -Eq 'APPLICATION FAILED TO START|Connection refused|OutOfMemoryError|Communications link failure'; then
  printf '%s\n' "$logs" | grep -E 'APPLICATION FAILED TO START|Connection refused|OutOfMemoryError|Communications link failure' | tail -30
  exit 1
fi
count=$(printf '%s\n' "$logs" | grep -Ec '"level":"ERROR"| ERROR ' || true)
echo "启动日志无致命错误；ERROR 行数：$count"
'@
    $logScript = $logScript.Replace('$REMOTE_DIR', $RemoteDir).Replace('${SINCE}', $logSince)
    Invoke-Remote $logScript "启动日志检查"

Write-Host "`n全部服务已就绪：" -ForegroundColor Green
Write-Host "  工作台   http://${ServerHost}:8095"
Write-Host "  Langfuse http://${ServerHost}:3005"
