$ErrorActionPreference = "Stop"

function Import-IpeDotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "未找到环境文件：$Path"
    }
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            return
        }
        if ($line -match '^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            $name = $matches[1]
            $value = $matches[2].Trim()
            if ($value.Length -ge 2) {
                $first = [string][char]$value[0]
                $last = [string][char]$value[$value.Length - 1]
                $doubleQuote = [string][char]34
                $singleQuote = [string][char]39
                if (($first -eq $doubleQuote -and $last -eq $doubleQuote) -or
                    ($first -eq $singleQuote -and $last -eq $singleQuote)) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
            }
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Get-IpeJavaHome {
    if ($env:IPE_JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:IPE_JAVA_HOME "bin\java.exe"))) {
        return $env:IPE_JAVA_HOME
    }
    $fallback = "D:\develop\jdk\jdk-21"
    if (Test-Path -LiteralPath (Join-Path $fallback "bin\java.exe")) {
        return $fallback
    }
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    throw "未找到 JDK 21。请设置 IPE_JAVA_HOME 或 JAVA_HOME。"
}

function Get-IpeMavenCommand {
    $fallback = "D:\develop\apache-maven-3.8.8\bin\mvn.cmd"
    if (Test-Path -LiteralPath $fallback) {
        return $fallback
    }
    $command = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $command = Get-Command "mvn" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "未找到 Maven。请安装 Maven 或使用默认路径 D:\develop\apache-maven-3.8.8。"
}

function Get-IpeMavenLocalRepository {
    if ($env:IPE_MAVEN_REPOSITORY -and (Test-Path -LiteralPath $env:IPE_MAVEN_REPOSITORY)) {
        return $env:IPE_MAVEN_REPOSITORY
    }

    $settingsFiles = New-Object System.Collections.Generic.List[string]
    $mavenCommand = Get-IpeMavenCommand
    $mavenHome = Split-Path -Parent (Split-Path -Parent $mavenCommand)
    $settingsFiles.Add((Join-Path $mavenHome "conf\settings.xml"))
    if ($env:MAVEN_HOME) {
        $settingsFiles.Add((Join-Path $env:MAVEN_HOME "conf\settings.xml"))
    }
    if ($env:M2_HOME) {
        $settingsFiles.Add((Join-Path $env:M2_HOME "conf\settings.xml"))
    }
    $settingsFiles.Add((Join-Path $HOME ".m2\settings.xml"))

    foreach ($settings in ($settingsFiles | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $settings)) {
            continue
        }
        try {
            [xml]$xml = Get-Content -LiteralPath $settings -Raw
            $configured = [string]$xml.settings.localRepository
            if ($configured) {
                $configured = $configured.Replace('${user.home}', $HOME)
            }
            if ($configured -and (Test-Path -LiteralPath $configured)) {
                return $configured
            }
        } catch {
            # 设置文件异常时继续尝试默认仓库，不输出文件内容。
        }
    }
    return (Join-Path $HOME ".m2\repository")
}

function Find-IpePostgresJdbcJar {
    $repository = Get-IpeMavenLocalRepository
    $root = Join-Path $repository "org\postgresql\postgresql"
    if (-not (Test-Path -LiteralPath $root)) {
        throw "未找到 PostgreSQL JDBC 驱动。请先运行 Maven 构建，或用 IPE_MAVEN_REPOSITORY 指定仓库。"
    }
    $versionDirs = Get-ChildItem -LiteralPath $root -Directory | Where-Object {
        Test-Path -LiteralPath (Join-Path $_.FullName ("postgresql-" + $_.Name + ".jar"))
    }
    $selected = $versionDirs | Sort-Object {
        try { [version]$_.Name } catch { [version]"0.0" }
    } -Descending | Select-Object -First 1
    if (-not $selected) {
        throw "Maven 仓库中没有 PostgreSQL JDBC 驱动。"
    }
    return (Join-Path $selected.FullName ("postgresql-" + $selected.Name + ".jar"))
}

function Invoke-IpeAiJdbc {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("query", "update")]
        [string]$Mode,
        [Parameter(Mandatory = $true)]
        [string]$Sql,
        [int]$TimeoutSeconds = 30
    )

    $javaHome = Get-IpeJavaHome
    $java = Join-Path $javaHome "bin\java.exe"
    $driver = Find-IpePostgresJdbcJar
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ipe-ai-jdbc-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tempDir | Out-Null

    $sourcePath = Join-Path $tempDir "IpeAiJdbc.java"
    $sqlPath = Join-Path $tempDir "operation.sql"
    $source = @'
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class IpeAiJdbc {
    private IpeAiJdbc() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: IpeAiJdbc <query|update> <sql-file>");
        }
        String mode = args[0];
        String sql = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
        String url = requireEnv("IPE_AI_JDBC_URL");
        String user = requireEnv("IPE_AI_JDBC_USER");
        String password = System.getenv("IPE_AI_JDBC_PASSWORD");
        if (password == null) {
            password = "";
        }
        Class.forName("org.postgresql.Driver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            if ("update".equals(mode)) {
                connection.setAutoCommit(false);
            }
            try {
                if ("update".equals(mode)) {
                    requireOnlineAgentSchema(connection);
                }
                List<String> statements = split(sql);
                if ("query".equals(mode)) {
                    executeQuery(connection, statements);
                } else {
                    executeUpdate(connection, statements);
                    connection.commit();
                }
            } catch (Exception exception) {
                if ("update".equals(mode)) {
                    connection.rollback();
                }
                throw exception;
            }
        }
    }

    private static void requireOnlineAgentSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select to_regclass('ipe_ai.ai_publication')")) {
            if (!result.next() || result.getString(1) == null) {
                throw new SQLException("ipe_ai 在线 Agent 表未初始化，请先启动 Control 执行 Flyway 迁移");
            }
        }
    }

    private static void executeQuery(Connection connection, List<String> statements) throws SQLException {
        for (String sql : statements) {
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = result.getMetaData();
                int count = metadata.getColumnCount();
                StringBuilder header = new StringBuilder("COLUMNS");
                for (int index = 1; index <= count; index++) {
                    header.append('\t').append(escape(metadata.getColumnLabel(index)));
                }
                System.out.println(header);
                while (result.next()) {
                    StringBuilder row = new StringBuilder("ROW");
                    for (int index = 1; index <= count; index++) {
                        row.append('\t').append(escape(result.getObject(index)));
                    }
                    System.out.println(row);
                }
            }
        }
    }

    private static void executeUpdate(Connection connection, List<String> statements) throws SQLException {
        long total = 0;
        int index = 0;
        for (String sql : statements) {
            index++;
            try (Statement statement = connection.createStatement()) {
                int affected = statement.executeUpdate(sql);
                total += affected;
                System.out.println("AFFECTED\t" + index + "\t" + affected);
            }
        }
        System.out.println("TOTAL\t" + total);
    }

    private static List<String> split(String sql) {
        List<String> result = new ArrayList<>();
        for (String item : sql.split(";\\s*(?:\\r?\\n|$)")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("missing environment variable: " + name);
        }
        return value;
    }

    private static String escape(Object value) {
        if (value == null) {
            return "<null>";
        }
        return String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
'@

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($sourcePath, $source, $utf8)
    [System.IO.File]::WriteAllText($sqlPath, $Sql, $utf8)

    $oldUrl = $env:IPE_AI_JDBC_URL
    $oldUser = $env:IPE_AI_JDBC_USER
    $oldPassword = $env:IPE_AI_JDBC_PASSWORD
    try {
        $env:IPE_AI_JDBC_URL = $env:IPE_AI_DB_URL
        if (-not $env:IPE_AI_JDBC_URL) {
            $env:IPE_AI_JDBC_URL = $env:IPE_OPS_DB_URL
        }
        $env:IPE_AI_JDBC_USER = $env:IPE_AI_DB_USER
        if (-not $env:IPE_AI_JDBC_USER) {
            $env:IPE_AI_JDBC_USER = $env:IPE_OPS_DB_USER
        }
        $env:IPE_AI_JDBC_PASSWORD = $env:IPE_AI_DB_PASSWORD
        if (-not $env:IPE_AI_JDBC_PASSWORD) {
            $env:IPE_AI_JDBC_PASSWORD = $env:IPE_OPS_DB_PASSWORD
        }
        if (-not $env:IPE_AI_JDBC_URL) {
            throw "未配置 IPE_AI_DB_URL 或 IPE_OPS_DB_URL"
        }
        if (-not $env:IPE_AI_JDBC_USER) {
            throw "未配置 IPE_AI_DB_USER 或 IPE_OPS_DB_USER"
        }

        $output = & $java "-Dfile.encoding=UTF-8" "--class-path" $driver $sourcePath $Mode $sqlPath 2>&1
        if ($LASTEXITCODE -ne 0) {
            $message = ($output | Out-String)
            foreach ($secret in @($env:IPE_AI_JDBC_PASSWORD, $env:IPE_AI_DB_PASSWORD, $env:IPE_OPS_DB_PASSWORD)) {
                if ($secret) {
                    $message = $message.Replace([string]$secret, "***")
                }
            }
            throw ("JDBC 操作失败：" + $message.Trim())
        }
    } finally {
        $env:IPE_AI_JDBC_URL = $oldUrl
        $env:IPE_AI_JDBC_USER = $oldUser
        $env:IPE_AI_JDBC_PASSWORD = $oldPassword
        Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    if ($Mode -eq "query") {
        $headers = @()
        $rows = New-Object System.Collections.Generic.List[object]
        foreach ($line in @($output)) {
            $text = [string]$line
            if ($text.StartsWith("COLUMNS`t")) {
                $headers = @($text.Substring(8).Split("`t"))
            } elseif ($text.StartsWith("ROW`t")) {
                $values = @($text.Substring(4).Split("`t"))
                $row = [ordered]@{}
                for ($index = 0; $index -lt $headers.Count; $index++) {
                    $row[$headers[$index]] = $values[$index]
                }
                $rows.Add([pscustomobject]$row)
            }
        }
        return $rows.ToArray()
    }

    $affected = New-Object System.Collections.Generic.List[object]
    $total = $null
    foreach ($line in @($output)) {
        $text = [string]$line
        if ($text.StartsWith("AFFECTED`t")) {
            $parts = $text.Split("`t")
            $affected.Add([pscustomobject]@{ Index = [int]$parts[1]; Count = [long]$parts[2] })
        } elseif ($text.StartsWith("TOTAL`t")) {
            $total = [long]$text.Substring(6)
        }
    }
    return [pscustomobject]@{ Affected = $affected.ToArray(); Total = $total }
}

function Wait-IpeHttpUp {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$TimeoutSeconds = 120,
        [int]$RequestTimeoutSeconds = 3,
        [int]$ProcessId = 0
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        if ($ProcessId -gt 0 -and -not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw "等待 $Uri 时进程已退出：pid=$ProcessId"
        }
        try {
            $response = Invoke-RestMethod -Uri $Uri -Method Get -TimeoutSec $RequestTimeoutSeconds
            if ($response -and ([string]$response.status).ToUpperInvariant() -eq "UP") {
                return $response
            }
            $lastError = "status=" + [string]$response.status
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "等待 $Uri 健康检查超时：$lastError"
}

function Get-IpeOwnedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$PidFile,
        [Parameter(Mandatory = $true)][string]$JarName
    )

    if (-not (Test-Path -LiteralPath $PidFile)) {
        return $null
    }
    $raw = (Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    $processId = 0
    if (-not [int]::TryParse([string]$raw, [ref]$processId)) {
        return $null
    }
    $process = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $processId) -ErrorAction SilentlyContinue
    if (-not $process -or -not $process.CommandLine) {
        return $null
    }
    if (-not $process.CommandLine.Contains($JarName)) {
        return $null
    }
    return [pscustomobject]@{ Id = $processId; CommandLine = $process.CommandLine }
}

function Stop-IpeOwnedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$PidFile,
        [Parameter(Mandatory = $true)][string]$JarName,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $owned = Get-IpeOwnedProcess -PidFile $PidFile -JarName $JarName
    if (-not $owned) {
        return
    }
    Stop-Process -Id $owned.Id -Force
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $owned.Id -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
            Write-Host ("[ipe-ai] 已停止 {0} pid={1}" -f $Label, $owned.Id)
            return
        }
        Start-Sleep -Milliseconds 300
    }
    throw "停止 $Label 超时：pid=$($owned.Id)"
}

function Get-IpePortPids {
    param([Parameter(Mandatory = $true)][int]$Port)

    $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if (-not $connections) {
        return @()
    }
    return @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
}
