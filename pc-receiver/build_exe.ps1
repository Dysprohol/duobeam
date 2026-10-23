# Builds dist\DuoBeam-pc-<version>.exe: one file with Python, FFmpeg (through PyAV) and SDL (through pygame) inside.
# Needs Python 3.11+ on PATH; the libraries go into .venv next to this script.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path .venv)) { python -m venv .venv }
.\.venv\Scripts\python.exe -m pip install --disable-pip-version-check -q -r requirements.txt
$version = (Get-Content ..\version.properties | Where-Object { $_ -match '^version=' }) -replace '^version=', ''
$inScript = (Select-String -Path duobeam_receiver.py -Pattern "^VERSION = '([^']+)'").Matches[0].Groups[1].Value
if ($inScript -ne $version) { throw "duobeam_receiver.py has VERSION $inScript, version.properties has $version" }
.\.venv\Scripts\python.exe -m PyInstaller --noconfirm --clean --onefile --windowed --name "DuoBeam-pc-$version" `
    --icon icon.ico --add-data "..\docs\img\icon.png;." --add-data "..\tizen-receiver\img\bg.jpg;." duobeam_receiver.py
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller failed' }
Write-Host "BUILD_OK dist\DuoBeam-pc-$version.exe"
