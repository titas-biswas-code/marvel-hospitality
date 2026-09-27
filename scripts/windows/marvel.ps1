<#
.SYNOPSIS
  Windows front end for the repository: the Makefile's commands, needing nothing but Docker Desktop.

.DESCRIPTION
  Run it through marvel.cmd in the repository root (.\marvel <command>), which starts it with this script's
  execution policy, so Windows' script-blocking default needs no change. Starting and stopping the stack calls
  `docker compose` directly; everything that needs bash, curl or jq (tokens, the bank simulator, the smoke test,
  the dead-letter replay) runs in the `tools` container (infra/tools), so each script exists once and behaves the
  same on every platform. Works in Windows PowerShell 5.1 and PowerShell 7.
#>
# No param() block on purpose: arguments such as `--max 5` or `--property AMS01` are passed through to the scripts,
# and an advanced script would reject them as unknown parameters.
$Command = 'help'
$Rest = @()
if ($args.Count -gt 0) { $Command = [string] $args[0] }
if ($args.Count -gt 1) { $Rest = @($args[1..($args.Count - 1)]) }

Set-StrictMode -Version 3
# Failures are detected through exit codes. 'Stop' would also turn a native command's redirected stderr into a
# terminating error in Windows PowerShell 5.1.
$ErrorActionPreference = 'Continue'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..') -ErrorAction Stop).Path
$ComposeFile = Join-Path $Root 'infra/docker-compose.yml'
$EnvFile = Join-Path $Root 'infra/.env'
$EnvExample = Join-Path $Root 'infra/.env.example'
$Projects = @('platform', 'room-reservation-service', 'bank-transfer-payment-service',
              'credit-card-payment-service', 'notification-service')
$OnWindows = $true
if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows) { $OnWindows = $false }

# ------------------------------------------------------------------------------------------------ helpers

function Fail([string] $Message) {
    Write-Host "error: $Message" -ForegroundColor Red
    exit 1
}

function Assert-ExitCode([string] $What) {
    if ($LASTEXITCODE -ne 0) { Fail "$What failed (exit code $LASTEXITCODE)" }
}

function Confirm-Yes([string] $Question) {
    $answer = Read-Host "$Question [y/N]"
    return ($answer -match '^(y|yes)$')
}

function Initialize-EnvFile {
    if (-not (Test-Path $EnvFile)) {
        Copy-Item $EnvExample $EnvFile -ErrorAction Stop
        Write-Host "created infra/.env from infra/.env.example (local development defaults)"
    }
}

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail "Docker is not installed. Run .\marvel init for what to install."
    }
    $os = & docker version --format '{{.Server.Os}}' 2>$null
    if ($LASTEXITCODE -ne 0) { Fail "the Docker engine does not answer. Start Docker Desktop and try again." }
    if ($os -ne 'linux') { Fail "Docker Desktop runs Windows containers. Switch it to Linux containers (tray icon menu)." }
}

# docker compose with this repository's file and .env; output goes straight to the console.
function Invoke-Compose {
    & docker compose -f $ComposeFile --env-file $EnvFile @args
    Assert-ExitCode "docker compose $($args -join ' ')"
}

function Get-AppServiceName {
    $services = & docker compose -f $ComposeFile --env-file $EnvFile --profile apps config --services
    Assert-ExitCode 'docker compose config'
    return @($services | Where-Object { $_ -and $_ -ne 'connect-init' })
}

# The tools container: bash, curl, jq, make, python3 and the Docker CLI; localhost:<port> inside it reaches the
# services (infra/tools/entrypoint.sh). Simple functions (no param block) so arguments pass through untouched.
function Initialize-ToolImage {
    & docker image inspect marvel-hospitality/tools:local *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "building the tools image (once)..."
        Invoke-Compose --profile tools build tools
    }
}

function Get-ToolRunArgument {
    return @('compose', '--progress', 'quiet', '-f', $ComposeFile, '--env-file', $EnvFile, '--profile', 'tools',
             'run', '--rm')
}

function Invoke-ToolCommand {
    Initialize-ToolImage
    $run = Get-ToolRunArgument
    & docker @run -T tools @args
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Enter-ToolShell {
    Initialize-ToolImage
    $run = Get-ToolRunArgument
    & docker @run tools bash
}

function Test-ContractCopy {
    $provider = Join-Path $Root 'credit-card-payment-service/src/main/resources/openapi/credit-card-payment-api.yaml'
    $consumer = Join-Path $Root 'room-reservation-service/src/main/resources/openapi/credit-card-payment-api.yaml'
    if ((Get-FileHash $provider).Hash -ne (Get-FileHash $consumer).Hash) {
        Fail "credit-card spec copies differ: $provider and $consumer"
    }
    Write-Host "credit-card spec copies identical"
}

function Invoke-Gradle([string] $Task) {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Fail "Java is not installed (needed to run Gradle). Run .\marvel init to install a JDK."
    }
    Test-ContractCopy
    foreach ($project in $Projects) {
        Write-Host "==> $project"
        Push-Location (Join-Path $Root $project)
        try {
            if ($OnWindows) { & .\gradlew.bat $Task --console=plain } else { & ./gradlew $Task --console=plain }
            Assert-ExitCode "gradle $Task in $project"
        } finally {
            Pop-Location
        }
    }
}

# ------------------------------------------------------------------------------------------------ init

function Invoke-Init {
    Write-Host "Checking this machine for marvel-hospitality...`n"
    $blocking = $false

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Host "[missing] Docker Desktop" -ForegroundColor Red
        Write-Host "  Install it from https://docs.docker.com/desktop/setup/install/windows-install/"
        Write-Host "  Its installer sets up the WSL 2 (or Hyper-V) backend it needs; this script does not."
        Write-Host "  Inside a virtual machine, enable nested virtualization first (Proxmox: CPU type 'host')."
        exit 1
    }
    $os = & docker version --format '{{.Server.Os}}' 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[missing] Docker engine not running: start Docker Desktop, then run .\marvel init again." -ForegroundColor Red
        exit 1
    }
    if ($os -ne 'linux') {
        Write-Host "[wrong mode] Docker Desktop runs Windows containers: switch to Linux containers (tray icon)." -ForegroundColor Red
        exit 1
    }
    Write-Host "[ok] Docker engine running Linux containers"

    $composeVersion = & docker compose version --short 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[missing] Docker Compose v2: update Docker Desktop." -ForegroundColor Red
        $blocking = $true
    } else {
        Write-Host "[ok] Docker Compose $composeVersion"
    }

    $memBytes = [int64](& docker info --format '{{.MemTotal}}')
    $memGb = [math]::Round($memBytes / 1GB, 1)
    if ($memGb -lt 7.5) {
        Write-Host "[warn] Docker has $memGb GB of memory; the stack needs about 8 GB." -ForegroundColor Yellow
        Write-Host "  With the WSL 2 backend Docker gets half the machine's memory by default: give the machine (or VM)"
        Write-Host "  16 GB, or raise the limit in %UserProfile%\.wslconfig ([wsl2] memory=10GB), then restart Docker Desktop."
    } else {
        Write-Host "[ok] Docker memory: $memGb GB"
    }

    # Line endings: .gitattributes keeps scripts LF, but a clone made before it existed has CRLF, which breaks the
    # scripts inside Linux containers.
    $probe = [System.IO.File]::ReadAllBytes((Join-Path $Root 'infra/kafka/create-topics.sh'))
    if ($probe -contains 13) {
        Write-Host "[problem] Scripts in this clone have Windows (CRLF) line endings; the containers cannot run them." -ForegroundColor Red
        if ((Get-Command git -ErrorAction SilentlyContinue) -and
            (Confirm-Yes "  Re-check out every file with the repository's line endings? This DISCARDS uncommitted changes")) {
            & git -C $Root rm -q -r --cached .
            & git -C $Root reset -q --hard
            Assert-ExitCode 'git reset'
            Write-Host "[ok] Line endings fixed"
        } else {
            Write-Host "  Or clone the repository again."
            $blocking = $true
        }
    } else {
        Write-Host "[ok] Script line endings"
    }

    $running = & docker compose -f $ComposeFile --env-file $EnvFile --profile apps ps -q 2>$null
    if ($running) {
        Write-Host "[ok] The stack is already running (port check skipped)"
    } elseif ($OnWindows) {
        $ports = 3000, 4317, 4318, 5432, 8080, 8081, 8082, 8083, 8088, 8090, 8180, 9090, 9094
        $busy = @($ports | Where-Object { Get-NetTCPConnection -State Listen -LocalPort $_ -ErrorAction SilentlyContinue })
        if ($busy.Count -gt 0) {
            Write-Host "[problem] Ports already in use: $($busy -join ', '). Stop what uses them first." -ForegroundColor Red
            $blocking = $true
        } else {
            Write-Host "[ok] Ports free"
        }
    }

    Initialize-EnvFile
    Write-Host "[ok] infra/.env"

    Write-Host "`nOptional (skip both to just run and demo the system):"
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (Get-Command java -ErrorAction SilentlyContinue) {
        Write-Host "[ok] Java (for .\marvel build-all / test-all)"
    } elseif ($winget -and (Confirm-Yes "  Install JDK 25 (Eclipse Temurin, about 200 MB) with winget? Only needed to run the tests")) {
        & winget install --id EclipseAdoptium.Temurin.25.JDK --exact --accept-package-agreements --accept-source-agreements
        Write-Host "  Open a new terminal so java is on PATH."
    } else {
        Write-Host "[skip] Java: only needed for .\marvel build-all / test-all (any JDK 17+ works)"
    }
    $postman = $null
    if ($OnWindows) { $postman = Join-Path $env:LOCALAPPDATA 'Postman/Postman.exe' }
    if ($postman -and (Test-Path $postman)) {
        Write-Host "[ok] Postman"
    } elseif ($winget -and (Confirm-Yes "  Install Postman (desktop app, about 400 MB) with winget? The demo collection is in postman/")) {
        & winget install --id Postman.Postman --exact --accept-package-agreements --accept-source-agreements
    } else {
        Write-Host "[skip] Postman: the same demos run from .\marvel shell or .\marvel smoke"
    }

    if ($blocking) { Fail "fix the problems above, then run .\marvel init again" }
    Write-Host "`nReady. Next: .\marvel up-apps" -ForegroundColor Green
}

# ------------------------------------------------------------------------------------------------ commands

function Show-Help {
    Write-Host @"
Usage: .\marvel <command> [arguments]      (make equivalent in brackets)

  init                          check this machine, create infra/.env, offer optional tools
  up-apps                       build and start everything, register the connectors   [make up-apps]
  up                            start the infrastructure only                           [make up]
  down                          stop everything, keep the data                           [make down]
  clean                         remove containers, data volumes and built images         [make clean]
  reset / reset-apps            wipe all data, start infra (or everything) again         [make reset / reset-apps]
  logs                          follow the logs of every container                       [make logs]
  smoke                         run the end-to-end smoke test (infra/e2e/smoke.sh)       [make smoke]
  shell                         bash with curl, jq, make; localhost reaches the services; run the README demo here
  token [user]                  access token for alice (default), bob or carol           [make token USER=bob]
  client-token [client]         service-account token (default bank-simulator)           [make client-token]
  sim <script> [args]           a bank simulator script, e.g. sim pay-in-full.sh --property AMS01 --reservation P1234567
  replay-dlt <topic> [--max N] [--dry-run]   replay dead letters                        [make replay-dlt]
  check-contracts               the two credit-card spec copies are identical            [make check-contracts]
  build-all / test-all          gradle build / test of platform and every service (needs Java)
"@
}

switch ($Command) {
    'help' { Show-Help }
    'init' { Invoke-Init }
    'up' {
        Assert-Docker; Initialize-EnvFile
        Invoke-Compose up -d --wait
    }
    'up-apps' {
        Assert-Docker; Initialize-EnvFile
        $services = Get-AppServiceName
        Invoke-Compose --profile apps up -d --wait --build @services
        # connect-init is a one-shot job: registers the Debezium connectors, exit code is the real signal.
        Invoke-Compose --profile apps run --rm connect-init
    }
    'down' { Assert-Docker; Initialize-EnvFile; Invoke-Compose --profile apps down }
    'clean' {
        Assert-Docker; Initialize-EnvFile
        Invoke-Compose --profile apps down -v --remove-orphans
        $images = & docker compose -f $ComposeFile --env-file $EnvFile --profile apps --profile tools config --images |
            Where-Object { $_ -like 'marvel-hospitality/*' }
        if ($images) { & docker image rm -f @images 2>$null | Out-Null }
    }
    'reset' {
        Assert-Docker; Initialize-EnvFile
        Invoke-Compose --profile apps down -v --remove-orphans
        Invoke-Compose up -d --wait
    }
    'reset-apps' {
        Assert-Docker; Initialize-EnvFile
        Invoke-Compose --profile apps down -v --remove-orphans
        $services = Get-AppServiceName
        Invoke-Compose --profile apps up -d --wait --build @services
        Invoke-Compose --profile apps run --rm connect-init
    }
    'logs' { Assert-Docker; Initialize-EnvFile; Invoke-Compose --profile apps logs -f }
    'smoke' { Assert-Docker; Initialize-EnvFile; Invoke-ToolCommand bash infra/e2e/smoke.sh @Rest }
    'shell' { Assert-Docker; Initialize-EnvFile; Enter-ToolShell }
    'token' {
        Assert-Docker; Initialize-EnvFile
        $user = 'alice'; if ($Rest.Count -gt 0) { $user = $Rest[0] }
        Invoke-ToolCommand make -s token "USER=$user"
    }
    'client-token' {
        Assert-Docker; Initialize-EnvFile
        $client = 'bank-simulator'; if ($Rest.Count -gt 0) { $client = $Rest[0] }
        Invoke-ToolCommand make -s client-token "CLIENT=$client"
    }
    'sim' {
        if ($Rest.Count -eq 0) { Fail "usage: .\marvel sim <script> [args]  (scripts: bank-transfer-simulator/scripts)" }
        Assert-Docker; Initialize-EnvFile
        $script, $scriptArgs = $Rest
        Invoke-ToolCommand bash "bank-transfer-simulator/scripts/$script" @scriptArgs
    }
    'replay-dlt' {
        if ($Rest.Count -eq 0) { Fail "usage: .\marvel replay-dlt <topic> [--max N] [--dry-run]" }
        Assert-Docker; Initialize-EnvFile
        Invoke-ToolCommand bash scripts/replay-dlt.sh @Rest
    }
    'check-contracts' { Test-ContractCopy }
    'build-all' { Invoke-Gradle build }
    'test-all' { Invoke-Gradle test }
    default { Show-Help; Fail "unknown command '$Command'" }
}
