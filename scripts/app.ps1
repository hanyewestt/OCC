[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'reset', 'start-pgadmin', 'stop-pgadmin')]
    [string]$Action = 'start',
    [string]$ProjectName,
    [switch]$Yes,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output 'Usage: ./scripts/app.ps1 <start|stop|reset|start-pgadmin|stop-pgadmin> [-ProjectName name] [-Yes]'
    Write-Output 'Reset deletes the selected project database and pgAdmin volumes, then rebuilds without layer cache.'
    Write-Output '-Yes skips the reset confirmation. Stop preserves data; stop-pgadmin stops only pgAdmin.'
    exit 0
}

try {
    if ($Yes -and $Action -ne 'reset') { throw '-Yes is only valid with reset.' }
    if ($ProjectName -and $ProjectName -cnotmatch '^[a-z0-9][a-z0-9_-]*$') {
        throw 'ProjectName must contain lowercase letters, digits, underscores or hyphens.'
    }
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $envFile = Join-Path $repoRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        throw 'Missing root .env. Copy .env.example to .env and configure it first.'
    }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is not installed or is not on PATH.' }
    & docker info --format '{{.ServerVersion}}' > $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable. Start Docker Desktop/Engine first.' }

    # Explicit local files prevent COMPOSE_FILE from selecting a deployment/test stack.
    $composeArgs = @('compose', '--project-directory', $repoRoot, '--env-file', $envFile,
        '-f', (Join-Path $repoRoot 'compose.yaml'), '-f', (Join-Path $repoRoot 'compose.override.yaml'))
    if ($ProjectName) { $composeArgs += @('--project-name', $ProjectName) }

    function Invoke-AppCompose {
        param([string[]]$Arguments)
        & docker @composeArgs @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit $LASTEXITCODE)." }
    }
    Invoke-AppCompose -Arguments @('config', '-q')
    # Resolve only the project identity; never print resolved configuration/secrets.
    $configJson = & docker @composeArgs config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the Compose project.' }
    try { $resolvedProject = ($configJson | ConvertFrom-Json).name }
    catch { throw 'Unable to read the Compose project identity.' }
    $configJson = $null
    if (-not $resolvedProject -or $resolvedProject -cnotmatch '^[a-z0-9][a-z0-9_-]*$') {
        throw 'Unable to determine a valid Compose project name.'
    }
    if (-not $ProjectName) { $composeArgs += @('--project-name', $resolvedProject) }
    Write-Host "OCC local project: $resolvedProject; action: $Action"

    switch ($Action) {
        'start' { Invoke-AppCompose -Arguments @('up', '-d', '--build', '--wait') }
        'stop' { Invoke-AppCompose -Arguments @('--profile', 'tools', 'down', '--remove-orphans') }
        'reset' {
            if (-not $Yes) {
                $answer = Read-Host "Delete containers, networks, database and pgAdmin data for '$resolvedProject'? Type RESET"
                if ($answer -cne 'RESET') { Write-Host 'Reset cancelled.'; exit 0 }
            }
            Invoke-AppCompose -Arguments @('--profile', 'tools', 'down', '--volumes', '--remove-orphans')
            Invoke-AppCompose -Arguments @('--profile', 'tools', 'build', '--pull', '--no-cache')
            Invoke-AppCompose -Arguments @('up', '-d', '--force-recreate', '--wait')
        }
        'start-pgadmin' { Invoke-AppCompose -Arguments @('--profile', 'tools', 'up', '-d', '--wait', 'pgadmin') }
        'stop-pgadmin' { Invoke-AppCompose -Arguments @('--profile', 'tools', 'stop', 'pgadmin') }
    }
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
