# meowify-android setup script for Windows PowerShell
# Run from inside the meowify-android\ folder:
#   powershell -ExecutionPolicy Bypass -File setup.ps1

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Assets = Join-Path $ScriptDir "app\src\main\assets"

Write-Host ""
Write-Host "meowify android setup" -ForegroundColor Cyan
Write-Host "=====================" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------------------
# 1. copy meowify.py
# ---------------------------------------------------------------------------
Write-Host "[1/3] looking for meowify.py..." -ForegroundColor Yellow

# search order: parent folder, same folder as this script, current working dir
$MeowifySrc = $null
$SearchPaths = @(
    (Join-Path (Split-Path $ScriptDir -Parent) "meowify.py"),
    (Join-Path $ScriptDir "meowify.py"),
    (Join-Path (Get-Location) "meowify.py")
)

foreach ($p in $SearchPaths) {
    if (Test-Path $p) {
        $MeowifySrc = $p
        break
    }
}

if ($null -eq $MeowifySrc) {
    Write-Host ""
    Write-Host "ERROR: meowify.py not found." -ForegroundColor Red
    Write-Host "Place meowify.py in one of these locations and re-run:" -ForegroundColor Red
    foreach ($p in $SearchPaths) {
        Write-Host "  $p" -ForegroundColor Gray
    }
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

New-Item -ItemType Directory -Force -Path $Assets | Out-Null
Copy-Item $MeowifySrc (Join-Path $Assets "meowify.py") -Force
Write-Host "  OK - copied meowify.py from $MeowifySrc" -ForegroundColor Green

$PatchSrc = Join-Path (Split-Path $ScriptDir -Parent) "android_patch.py"
if (-not (Test-Path $PatchSrc)) {
    Write-Host "ERROR: android_patch.py not found at $PatchSrc" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}
Copy-Item $PatchSrc (Join-Path $Assets "android_patch.py") -Force
Write-Host "  OK - copied android_patch.py from $PatchSrc" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 2. download static ffmpeg arm64
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "[2/3] checking ffmpeg..." -ForegroundColor Yellow

$FfmpegUrl  = "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.0/ffmpeg-linux-arm64"
$FfmpegDest = Join-Path $Assets "ffmpeg"

$NeedDownload = $true
if (Test-Path $FfmpegDest) {
    $ExistingSize = (Get-Item $FfmpegDest).Length
    if ($ExistingSize -gt 1000000) {
        $SizeMB = [math]::Round($ExistingSize / 1MB, 1)
        Write-Host "  OK - ffmpeg already present ($SizeMB MB), skipping" -ForegroundColor Green
        $NeedDownload = $false
    }
}

if ($NeedDownload) {
    Write-Host "  downloading arm64 ffmpeg (~40 MB), please wait..." -ForegroundColor Yellow
    try {
        $wc = New-Object System.Net.WebClient
        $wc.DownloadFile($FfmpegUrl, $FfmpegDest)
        $SizeMB = [math]::Round((Get-Item $FfmpegDest).Length / 1MB, 1)
        Write-Host "  OK - ffmpeg downloaded ($SizeMB MB)" -ForegroundColor Green
    }
    catch {
        Write-Host "  FAILED: $_" -ForegroundColor Red
        Write-Host ""
        Write-Host "  Download it manually from:" -ForegroundColor Yellow
        Write-Host "  $FfmpegUrl" -ForegroundColor Gray
        Write-Host "  Save as: $FfmpegDest" -ForegroundColor Gray
        Write-Host "  (the app will not be able to convert audio without it)" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# 3. copy motionui
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "[3/3] looking for motionui folder..." -ForegroundColor Yellow

$MuDest = Join-Path $Assets "motionui"
if (Test-Path $MuDest) { Remove-Item $MuDest -Recurse -Force }

$AlreadyPresent = (Test-Path $MuDest) -and ((Get-ChildItem $MuDest -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0)

if ($AlreadyPresent) {
    Write-Host "  OK - motionui already in assets\" -ForegroundColor Green
}
else {
    $MuSrc = $null
    $MuSearchPaths = @(
        (Join-Path (Split-Path $ScriptDir -Parent) "motionui"),
        (Join-Path $ScriptDir "motionui"),
        (Join-Path (Get-Location) "motionui")
    )

    foreach ($p in $MuSearchPaths) {
        if (Test-Path $p) {
            $MuSrc = $p
            break
        }
    }

    if ($null -ne $MuSrc) {
        New-Item -ItemType Directory -Force -Path $MuDest | Out-Null
        Copy-Item (Join-Path $MuSrc "*") $MuDest -Recurse -Force
        Write-Host "  OK - copied motionui from $MuSrc" -ForegroundColor Green
    }
    else {
        Write-Host ""
        Write-Host "  WARNING: motionui folder not found." -ForegroundColor Red
        Write-Host "  Searched:" -ForegroundColor Gray
        foreach ($p in $MuSearchPaths) {
            Write-Host "    $p" -ForegroundColor Gray
        }
        Write-Host "  Copy your motionui folder to one of those paths and re-run," -ForegroundColor Yellow
        Write-Host "  or copy it directly to:" -ForegroundColor Yellow
        Write-Host "  $MuDest" -ForegroundColor Gray
        Write-Host "  The app will build but the UI will not load correctly." -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# done
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Setup complete!" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next steps:" -ForegroundColor White
Write-Host "  1. Open the meowify-android\ folder in Android Studio" -ForegroundColor Gray
Write-Host "  2. Wait for Gradle sync (first time downloads ~200 MB)" -ForegroundColor Gray
Write-Host "  3. See BUILDING.md for full instructions" -ForegroundColor Gray
Write-Host ""
Read-Host "Press Enter to exit"
