$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo   = 'xtimmie/meowify'
$Root   = Join-Path $env:LOCALAPPDATA 'Programs\meowify'
$MuDir  = Join-Path $env:LOCALAPPDATA 'Programs\motionui'
$Venv   = Join-Path $Root 'venv'
$Bin    = Join-Path $Root 'bin'
$Tmp    = Join-Path $env:TEMP ('meowify-setup-' + [guid]::NewGuid().ToString('N'))
$Latest = "https://github.com/$Repo/releases/latest/download"

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Get-File($url, $dest) { Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing }
function Get-Sha($p) { (Get-FileHash -Algorithm SHA256 -Path $p).Hash.ToLower() }

function Find-Python {
    $cands = @()
    foreach ($n in 'python', 'python3') {
        $c = Get-Command $n -ErrorAction SilentlyContinue
        if ($c) { $cands += $c.Source }
    }
    $pl = Get-Command py -ErrorAction SilentlyContinue
    if ($pl) {
        try { $p = & py -3 -c 'import sys;print(sys.executable)' 2>$null; if ($p) { $cands += $p } } catch {}
    }
    $cands += (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\python.exe')
    foreach ($c in $cands) {
        if (-not (Test-Path $c)) { continue }
        if ($c -like '*WindowsApps*') { continue }
        try {
            $ok = & $c -c 'import sys,venv,ensurepip;print(int(sys.version_info >= (3,10)))' 2>$null
            if ($ok -eq '1') { return $c }
        } catch {}
    }
    return $null
}

New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
try {
    $py = Find-Python
    if (-not $py) {
        Step 'installing python 3.12 (per-user, no admin needed)'
        $inst = Join-Path $Tmp 'python-installer.exe'
        Get-File 'https://www.python.org/ftp/python/3.12.8/python-3.12.8-amd64.exe' $inst
        $p = Start-Process $inst -ArgumentList '/quiet', 'InstallAllUsers=0', 'PrependPath=0', 'Include_launcher=0', 'Include_test=0', 'Shortcuts=0' -Wait -PassThru
        if ($p.ExitCode -ne 0) { throw "python installer failed (exit $($p.ExitCode))" }
        $py = Find-Python
        if (-not $py) { throw 'python was installed but could not be found' }
    }
    Step "using python at $py"

    Step 'fetching latest release info'
    $manPath = Join-Path $Tmp 'manifest.json'
    Get-File "$Latest/manifest.json" $manPath
    $man = Get-Content -Raw -Encoding UTF8 $manPath | ConvertFrom-Json
    $tag = $man.tag
    $base = "https://github.com/$Repo/releases/download/$tag"

    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    foreach ($name in 'meowify.py', 'requirements.txt', 'launcher.pyw') {
        Step "downloading $name"
        $dest = Join-Path $Tmp $name
        Get-File "$base/$name" $dest
        $want = $man.files.$name
        if (-not $want) { throw "manifest has no hash for $name" }
        if ((Get-Sha $dest) -ne $want.ToLower()) { throw "$name failed the hash check" }
    }
    foreach ($name in 'meowify.py', 'requirements.txt', 'launcher.pyw') {
        Copy-Item (Join-Path $Tmp $name) (Join-Path $Root $name) -Force
    }

    $vpy = Join-Path $Venv 'Scripts\python.exe'
    $healthy = $false
    if (Test-Path $vpy) {
        try { & $vpy -c 'pass' 2>$null; if ($LASTEXITCODE -eq 0) { $healthy = $true } } catch {}
    }
    if (-not $healthy) {
        Step 'creating virtual environment'
        & $py -m venv $Venv --clear
        if ($LASTEXITCODE -ne 0) { throw 'venv creation failed' }
    }
    Step 'installing python packages (this takes a few minutes)'
    & $vpy -m pip install --upgrade pip --disable-pip-version-check
    & $vpy -m pip install -r (Join-Path $Root 'requirements.txt') --disable-pip-version-check
    if ($LASTEXITCODE -ne 0) { throw 'pip install failed' }

    if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue) -and -not (Test-Path (Join-Path $Bin 'ffmpeg.exe'))) {
        Step 'downloading ffmpeg'
        $z = Join-Path $Tmp 'ffmpeg.zip'
        Get-File 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip' $z
        Expand-Archive -Path $z -DestinationPath (Join-Path $Tmp 'ffmpeg') -Force
        New-Item -ItemType Directory -Force -Path $Bin | Out-Null
        $found = Get-ChildItem (Join-Path $Tmp 'ffmpeg') -Recurse -Include ffmpeg.exe, ffprobe.exe
        foreach ($f in $found) { Copy-Item $f.FullName $Bin -Force }
    }

    Step "installing motionui $($man.motionui)"
    $mz = Join-Path $Tmp 'motionui.zip'
    Get-File "https://github.com/xtimmie/motionui/archive/refs/tags/$($man.motionui).zip" $mz
    $mx = Join-Path $Tmp 'motionui'
    Expand-Archive -Path $mz -DestinationPath $mx -Force
    $inner = Get-ChildItem $mx -Directory | Select-Object -First 1
    if (-not $inner -or -not (Test-Path (Join-Path $inner.FullName 'motionui.js'))) { throw 'motionui download looks wrong' }
    if (Test-Path $MuDir) { Remove-Item $MuDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Split-Path $MuDir) | Out-Null
    Copy-Item $inner.FullName $MuDir -Recurse -Force

    $json = (@{ version = $man.version; tag = $tag; motionui = $man.motionui } | ConvertTo-Json)
    [IO.File]::WriteAllText((Join-Path $Root '.install.json'), $json, (New-Object Text.UTF8Encoding $false))

    Step 'creating start menu shortcut'
    $ws = New-Object -ComObject WScript.Shell
    $lnk = $ws.CreateShortcut((Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\meowify.lnk'))
    $lnk.TargetPath = Join-Path $Venv 'Scripts\pythonw.exe'
    $lnk.Arguments = '"' + (Join-Path $Root 'launcher.pyw') + '"'
    $lnk.WorkingDirectory = $Root
    $lnk.Save()

    Write-Host ''
    Write-Host "meowify $($man.version) installed to $Root" -ForegroundColor Green
    Write-Host 'start it from the start menu (search: meowify)' -ForegroundColor Green
    if (-not $env:MEOWIFY_NO_LAUNCH) {
        Start-Process (Join-Path $Venv 'Scripts\pythonw.exe') -ArgumentList ('"' + (Join-Path $Root 'launcher.pyw') + '"') -WorkingDirectory $Root
    }
}
finally {
    Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
}