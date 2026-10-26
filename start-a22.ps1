param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 3307,
    [string]$DbName = "a22",
    [string]$DbUsername = "root",
    [string]$DbPassword = $(if (-not [string]::IsNullOrWhiteSpace($env:A22_DB_PASSWORD)) { $env:A22_DB_PASSWORD } else { $env:MYSQL_ROOT_PASSWORD })
)

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $RootDir "backend"
$FrontendDir = Join-Path $RootDir "frontend"

function Resolve-Tool($Name, [string[]]$Candidates) {
    foreach ($candidate in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command -and $command.Source) { return $command.Source }
    }
    throw "$Name not found. Install $Name or add it to PATH."
}

$MavenCmd = Resolve-Tool "Maven" @(
    (Join-Path $RootDir "mvnw.cmd"),
    "D:\DevelopmentTools\apache-maven-3.9.16\bin\mvn.cmd",
    "D:\IntelliJ IDEA 2025.3\plugins\maven\lib\maven3\bin\mvn.cmd",
    "D:\Program Files\JetBrains\IntelliJ IDEA 2020.1.1\plugins\maven\lib\maven3\bin\mvn.cmd",
    "mvn.cmd",
    "mvn"
)
$NpmCmd = Resolve-Tool "npm" @(
    "D:\DevelopmentTools\nodejs\npm.cmd",
    "D:\web\nodejs\npm.cmd",
    "npm.cmd",
    "npm"
)
$MysqlCmd = $null
try {
    $MysqlCmd = Resolve-Tool "MySQL client" @(
    "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
    "C:\Program Files\MySQL\MySQL Server 8.3\bin\mysql.exe",
    "C:\Program Files\MySQL\MySQL Server 8.2\bin\mysql.exe",
    "C:\Program Files\MySQL\MySQL Server 8.1\bin\mysql.exe",
    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    "mysql.exe",
    "mysql"
    )
} catch {
    Write-Host "MySQL client not found; will skip SQL client pre-check and let Spring Boot JDBC verify the database." -ForegroundColor Yellow
}

function Require-File($Path, $Name) {
    if (-not (Test-Path $Path)) {
        throw "$Name not found: $Path"
    }
}

function Test-Port($Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(1000, $false)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}


function Start-HiddenPowerShell($Command, [hashtable]$Environment) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "powershell.exe"
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encodedCommand"
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    foreach ($key in $Environment.Keys) {
        $psi.EnvironmentVariables[$key] = [string]$Environment[$key]
    }
    [System.Diagnostics.Process]::Start($psi) | Out-Null
}

Require-File $MavenCmd "Maven"
Require-File $NpmCmd "npm"

if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    throw "Database password is required. Set `$env:A22_DB_PASSWORD or `$env:MYSQL_ROOT_PASSWORD, or pass -DbPassword."
}

if (-not (Test-Port $DbPort)) {
    throw "MySQL 8.4 is not listening on ${DbHost}:$DbPort. Start it with: docker compose up -d mysql"
}

if ($MysqlCmd) {
    Write-Host "Checking MySQL 8.4 connection on ${DbHost}:$DbPort ..." -ForegroundColor Cyan
    $mysqlArgs = @(
        "--protocol=TCP",
        "--ssl-mode=DISABLED",
        "--host=$DbHost",
        "--port=$DbPort",
        "--user=$DbUsername",
        "--password=$DbPassword",
        "--database=$DbName",
        "--connect-timeout=8",
        "-e",
        "SELECT VERSION() AS version, DATABASE() AS db, USER() AS user;"
    )
    $mysqlOutput = & $MysqlCmd @mysqlArgs 2>&1 | Out-String
    Write-Host $mysqlOutput.Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL pre-check failed. Verify the database account, password, and service state."
    }
    if ($mysqlOutput -notmatch '(?m)^\s*8\.4(?:\.|\s|$)') {
        throw "统一基线要求 MySQL 8.4，当前服务版本不符合。"
    }
}

$BackendLog = Join-Path $RootDir "backend-run-live.log"
$BackendErr = Join-Path $RootDir "backend-err-live.log"
$FrontendLog = Join-Path $RootDir "frontend-dev.log"
$FrontendErr = Join-Path $RootDir "frontend-dev.err"

$DatasourceUrl = "jdbc:mysql://${DbHost}:${DbPort}/${DbName}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"

$BackendCommand = @"
Set-Location '$BackendDir'
& '$MavenCmd' spring-boot:run
"@

$BackendEnvironment = @{
    SPRING_DATASOURCE_URL = $DatasourceUrl
    SPRING_DATASOURCE_USERNAME = $DbUsername
    SPRING_DATASOURCE_PASSWORD = $DbPassword
    A22_DB_PASSWORD = $DbPassword
    SERVER_PORT = $BackendPort
    FLINK_REST_URL = 'http://127.0.0.1:8081'
    KAFKA_BOOTSTRAP_SERVERS = '127.0.0.1:9092'
    FLINK_BOOTSTRAP_SERVERS = '127.0.0.1:9092'
    FLINK_JOB_JAR = 'D:/a22(3)/a22/flink-jobs/target/flink-jobs-1.0-SNAPSHOT.jar'
    FLINK_MYSQL_URL = $DatasourceUrl
}

$FrontendCommand = @"
`$env:Path = 'D:\web\nodejs;' + `$env:Path
`$env:VITE_API_TARGET = 'http://127.0.0.1:$BackendPort'
Set-Location '$FrontendDir'
& '$NpmCmd' run dev -- --host 127.0.0.1 --port $FrontendPort
"@

if (Test-Port $BackendPort) {
    Write-Host "Backend already responds on http://127.0.0.1:$BackendPort; skipping backend start." -ForegroundColor Yellow
} else {
    Write-Host "Starting backend on http://127.0.0.1:$BackendPort ..." -ForegroundColor Green
    $BackendLaunchCommand = "$BackendCommand *> '$BackendLog'"
    Start-HiddenPowerShell $BackendLaunchCommand $BackendEnvironment
    Start-Sleep -Seconds 5
}

if (Test-Port $FrontendPort) {
    Write-Host "Frontend already responds on http://127.0.0.1:$FrontendPort; skipping frontend start." -ForegroundColor Yellow
} else {
    Write-Host "Starting frontend on http://127.0.0.1:$FrontendPort ..." -ForegroundColor Green
    $FrontendLaunchCommand = "$FrontendCommand *> '$FrontendLog'"
    Start-Process powershell.exe -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-Command", $FrontendLaunchCommand
    ) -WindowStyle Hidden
}

Write-Host ""
Write-Host "A22 is starting." -ForegroundColor Cyan
Write-Host "Frontend: http://127.0.0.1:$FrontendPort"
Write-Host "Backend:  http://127.0.0.1:$BackendPort"
Write-Host "Backend log:  $BackendLog"
Write-Host "Frontend log: $FrontendLog"










