param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [switch]$RerunTasks
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$outputDir = Join-Path $ProjectRoot 'app\build\outputs\apk\public\debug'
$targetName = 'app-public-universal-debug.apk'
$targetPath = Join-Path $outputDir $targetName

$pushedLocation = $false
try {
    Push-Location $ProjectRoot
    $pushedLocation = $true
    $gradleArgs = @(':app:assemblePublicDebug')
    if ($RerunTasks) {
        $gradleArgs += '--rerun-tasks'
    }
    & .\gradlew @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    if ($pushedLocation) {
        Pop-Location
    }
}

if (!(Test-Path -LiteralPath $targetPath)) {
    throw "Target APK was not produced: $targetPath"
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$copyName = "RikkaHub-public-universal-debug-$timestamp.apk"
$copyPath = Join-Path $outputDir $copyName
Copy-Item -LiteralPath $targetPath -Destination $copyPath -Force

Get-Item -LiteralPath $targetPath, $copyPath | Select-Object FullName, Length, LastWriteTime
