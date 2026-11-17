# Start the local Python Tool Service. Configuration is loaded from config/application*.yaml.
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $projectRoot '.venv\Scripts\python.exe'
$localConfig = Join-Path $projectRoot 'config\application.local.yaml'
$localConfigExample = Join-Path $projectRoot 'config\application.local.example.yaml'

if (-not (Test-Path -LiteralPath $python)) {
    throw "Python virtual environment not found: $python. Create .venv and install requirements.txt first."
}

if (-not (Test-Path -LiteralPath $localConfig)) {
    Copy-Item -LiteralPath $localConfigExample -Destination $localConfig
    throw "Created config/application.local.yaml. Configure web-search and research-runtime credentials, then start again."
}

Push-Location $projectRoot
try {
    & $python -m app.main
} finally {
    Pop-Location
}
