param([Parameter(ValueFromRemainingArguments = $true)][string[]]$JavaArgs)
$ErrorActionPreference = 'Stop'
$java = Get-Command java.exe -ErrorAction SilentlyContinue
if ($java) {
    $javaPath = $java.Source
} elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaPath = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $portableRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $portableRoot) {
        $javaPath = Get-ChildItem -LiteralPath $portableRoot -Filter java.exe -Recurse |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not $javaPath) { throw 'Java 17+ is required.' }
Push-Location $PSScriptRoot
try {
    & $javaPath -jar (Join-Path $PSScriptRoot 'WorkStealing.jar') @JavaArgs
    if ($LASTEXITCODE -ne 0) { throw 'WorkStealing failed.' }
} finally { Pop-Location }
