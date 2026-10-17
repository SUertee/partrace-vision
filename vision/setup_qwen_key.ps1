param([switch]$CheckOnly, [switch]$Replace, [ValidateSet('anker','qianwen')][string]$Provider='anker')
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    $taskPython = Join-Path $PSScriptRoot '.venv-track\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $taskPython -PathType Leaf)) {
        throw 'Missing project environment .venv-track.'
    }
    if ($CheckOnly) {
        & $taskPython -X utf8 -m tools.qwen_credential_check --provider $Provider
        if ($LASTEXITCODE -ne 0) { throw 'Credential check failed. Run .\setup_qwen_key.ps1 to configure it.' }
        return
    }
    $taskKeyName = if ($Provider -eq 'anker') { 'anker-key.dpapi' } else { 'qwen-key.dpapi' }
    $taskKeyPath = Join-Path (Join-Path $env:LOCALAPPDATA 'StationVision') $taskKeyName
    if (-not $Replace -and ($env:STATION_VLM_API_KEY -or (Test-Path -LiteralPath $taskKeyPath))) {
        & $taskPython -X utf8 -m tools.qwen_credential_check --provider $Provider
        if ($LASTEXITCODE -ne 0) { throw 'Existing credential could not be loaded. Use -Replace to replace the private store.' }
        return
    }
    $taskSecret = Read-Host 'Paste the Qwen API key (hidden; press Enter to save)' -AsSecureString
    if ($taskSecret.Length -eq 0) { throw 'Empty key was not saved.' }
    $taskEncrypted = ConvertFrom-SecureString -SecureString $taskSecret
    $taskKeyDirectory = Split-Path -Parent $taskKeyPath
    New-Item -ItemType Directory -Path $taskKeyDirectory -Force | Out-Null
    [System.IO.File]::WriteAllText($taskKeyPath, $taskEncrypted, [System.Text.Encoding]::ASCII)
    $taskSecret.Dispose()
    & $taskPython -X utf8 -m tools.qwen_credential_check --provider $Provider
    if ($LASTEXITCODE -ne 0) { throw 'Saved credential failed the load check.' }
} finally {
    Pop-Location
}
