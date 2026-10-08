[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$jdkBin = $null
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $jdkBin = Join-Path $env:JAVA_HOME 'bin'
} elseif (Get-Command javac.exe -ErrorAction SilentlyContinue) {
    $jdkBin = Split-Path (Get-Command javac.exe).Source
} else {
    $portableRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $portableRoot) {
        $compiler = Get-ChildItem -LiteralPath $portableRoot -Filter javac.exe -Recurse |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($compiler) { $jdkBin = Split-Path $compiler.FullName }
    }
}
if (-not $jdkBin) { throw 'Install JDK 17+ or set JAVA_HOME to its directory.' }

$buildDir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
$sources = @(
    (Join-Path $PSScriptRoot 'practice3\Mandelbrot.java'),
    (Join-Path $PSScriptRoot 'practice4\WorkStealing.java')
)
& (Join-Path $jdkBin 'javac.exe') --release 17 -encoding UTF-8 -Xlint:all -d $buildDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
& (Join-Path $jdkBin 'jar.exe') --create --file (Join-Path $PSScriptRoot 'practice3\Mandelbrot.jar') --main-class practice3.Mandelbrot -C $buildDir practice3
if ($LASTEXITCODE -ne 0) { throw 'Mandelbrot JAR creation failed.' }
& (Join-Path $jdkBin 'jar.exe') --create --file (Join-Path $PSScriptRoot 'practice4\WorkStealing.jar') --main-class practice4.WorkStealing -C $buildDir practice4
if ($LASTEXITCODE -ne 0) { throw 'WorkStealing JAR creation failed.' }
Write-Output 'Built practice3/Mandelbrot.jar and practice4/WorkStealing.jar (Java 17+).'
