param(
    [switch]$SkipOpencode,
    [switch]$SkipWebapp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$PLUGIN_DIR = Join-Path $ROOT "packages/jetbrains-plugin"
$OPENCODE_DIR = Join-Path $ROOT "packages/opencode"
$APP_DIR = Join-Path $ROOT "packages/app"
$VERSION = [regex]::Match((Get-Content (Join-Path $PLUGIN_DIR "build.gradle.kts") -Raw), 'version\s*=\s*"([^"]+)"').Groups[1].Value
$DIST = Join-Path $PLUGIN_DIR "build/distributions/opencode-jetbrains-plugin-$VERSION.zip"
$BINARIES = @(
    (Join-Path $OPENCODE_DIR "dist/opencode-windows-arm64/bin/opencode.exe"),
    (Join-Path $OPENCODE_DIR "dist/opencode-windows-x64-baseline/bin/opencode.exe"),
    (Join-Path $OPENCODE_DIR "dist/opencode-darwin-arm64/bin/opencode"),
    (Join-Path $OPENCODE_DIR "dist/opencode-darwin-x64-baseline/bin/opencode"),
    (Join-Path $OPENCODE_DIR "dist/opencode-linux-arm64/bin/opencode"),
    (Join-Path $OPENCODE_DIR "dist/opencode-linux-arm64-musl/bin/opencode"),
    (Join-Path $OPENCODE_DIR "dist/opencode-linux-x64-baseline/bin/opencode"),
    (Join-Path $OPENCODE_DIR "dist/opencode-linux-x64-baseline-musl/bin/opencode")
)

function Step($Message) {
    Write-Host ""
    Write-Host "> $Message" -ForegroundColor Green
}

function Warn($Message) {
    Write-Host "! $Message" -ForegroundColor Yellow
}

function Format-Size($Path) {
    $item = Get-Item $Path
    $size = [double]$item.Length
    if ($size -ge 1GB) { return "{0:N2} GB" -f ($size / 1GB) }
    if ($size -ge 1MB) { return "{0:N2} MB" -f ($size / 1MB) }
    if ($size -ge 1KB) { return "{0:N2} KB" -f ($size / 1KB) }
    return "{0} B" -f $item.Length
}

function Test-BundledBins() {
    return $null -eq ($BINARIES | Where-Object { -not (Test-Path $_) } | Select-Object -First 1)
}

function Java-Version($Path) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Path
    $psi.Arguments = "-version"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $out = $proc.StandardOutput.ReadToEnd()
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    return (($out, $err) -join "`n").Split([Environment]::NewLine, [System.StringSplitOptions]::RemoveEmptyEntries)[0].Trim()
}

function Java-Major($JdkHome) {
    $java = Join-Path $JdkHome "bin/java.exe"
    if (-not (Test-Path $java)) {
        return -1
    }

    $line = Java-Version $java
    $m = [regex]::Match($line, 'version\s+"(?<v>\d+)')
    if (-not $m.Success) {
        return -1
    }

    return [int]$m.Groups['v'].Value
}

function Resolve-JavaHome() {
    $homes = @()

    if ($env:JAVA_HOME) {
        $homes += $env:JAVA_HOME
    }

    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) {
        $homes += Split-Path -Parent (Split-Path -Parent $javac.Source)
    }

    $userHome = [Environment]::GetFolderPath("UserProfile")
    $roots = @(
        (Join-Path $userHome ".jdks"),
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Eclipse Adoptium"
    )

    foreach ($root in $roots) {
        if (-not (Test-Path $root)) {
            continue
        }

        Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $homes += $_.FullName }
    }

    $items = $homes |
        Select-Object -Unique |
        ForEach-Object {
            [pscustomobject]@{
                Path  = $_
                Major = Java-Major $_
            }
        } |
        Where-Object { $_.Major -ge 21 }

    $exact = $items |
        Where-Object { $_.Major -eq 21 } |
        Sort-Object Path

    foreach ($item in @($exact) + @($items | Where-Object { $_.Major -ne 21 } | Sort-Object Major, Path)) {
        $java = Join-Path $item.Path "bin/java.exe"
        $javac = Join-Path $item.Path "bin/javac.exe"
        if ((Test-Path $java) -and (Test-Path $javac)) {
            return $item.Path
        }
    }

    throw "JDK 21+ not found. Install one or set JAVA_HOME to a JDK root."
}

function Invoke-GradleBuild() {
    $bat = Join-Path $PLUGIN_DIR "gradlew.bat"
    if (Test-Path $bat) {
        & $bat clean buildPlugin -x buildOpencode -x buildWebapp
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
        return
    }

    $jar = Join-Path $PLUGIN_DIR "gradle/wrapper/gradle-wrapper.jar"
    if (-not (Test-Path $jar)) {
        throw "Gradle wrapper JAR is missing: $jar"
    }

    $java = Join-Path $env:JAVA_HOME "bin/java.exe"
    & $java "-Dorg.gradle.appname=gradlew" "-classpath" $jar "org.gradle.wrapper.GradleWrapperMain" "clean" "buildPlugin" "-x" "buildOpencode" "-x" "buildWebapp"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

Step "Checking prerequisites"

$bun = Get-Command bun -ErrorAction SilentlyContinue
if (-not $bun) {
    throw "bun not found. Install from https://bun.sh"
}

$env:JAVA_HOME = Resolve-JavaHome
$java = Get-Command (Join-Path $env:JAVA_HOME "bin/java.exe") -ErrorAction Stop
$javac = Get-Command (Join-Path $env:JAVA_HOME "bin/javac.exe") -ErrorAction Stop
$javaVersion = Java-Version $java.Source
Write-Host "  bun  -> $($bun.Source) ($(& $bun.Source --version))"
Write-Host "  java -> $javaVersion"
Write-Host "  javac -> $($javac.Source)"
Write-Host "  home -> $env:JAVA_HOME"

if ($SkipOpencode) {
    Warn "Skipping opencode build (--skip-opencode)"
    if (-not (Test-BundledBins)) {
        throw "Bundled binaries not found. Run without --skip-opencode first."
    }
}
else {
    Step "Building opencode standalone binaries"
    Push-Location $OPENCODE_DIR
    try {
        & $bun.Source run build
        if ($LASTEXITCODE -ne 0) {
            throw "opencode build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
    if (-not (Test-BundledBins)) {
        throw "Build completed but required bundled binaries are missing."
    }
    Write-Host "  Binaries: $($BINARIES.Count) bundled targets ready"
}

if ($SkipWebapp) {
    Warn "Skipping webapp build (--skip-webapp)"
    $webapp = Get-ChildItem -Path (Join-Path $APP_DIR "dist/assets") -Filter "index-*.js" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $webapp) {
        throw "Webapp dist not found. Run without --skip-webapp first."
    }
}
else {
    Step "Building webapp (VITE_TARGET=jetbrains)"
    $oldTarget = $env:VITE_TARGET
    try {
        $env:VITE_TARGET = "jetbrains"
        Push-Location $APP_DIR
        try {
            & $bun.Source run build
            if ($LASTEXITCODE -ne 0) {
                throw "webapp build failed with exit code $LASTEXITCODE"
            }
        }
        finally {
            Pop-Location
        }
    }
    finally {
        if ($null -eq $oldTarget) {
            Remove-Item Env:VITE_TARGET -ErrorAction SilentlyContinue
        }
        else {
            $env:VITE_TARGET = $oldTarget
        }
    }
    Write-Host "  Webapp built -> $(Join-Path $APP_DIR "dist")"
}

Step "Building JetBrains plugin"
Push-Location $PLUGIN_DIR
try {
    Invoke-GradleBuild
}
finally {
    Pop-Location
}

if (-not (Test-Path $DIST)) {
    throw "Plugin ZIP not found after build: $DIST"
}

$size = Format-Size $DIST
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "Plugin ready - $size" -ForegroundColor Green
Write-Host "  $DIST" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  To install:"
Write-Host "  1. Open WebStorm"
Write-Host "  2. Settings -> Plugins -> gear -> Install Plugin from Disk"
Write-Host "  3. Select the ZIP above"
Write-Host "  4. Restart WebStorm"
