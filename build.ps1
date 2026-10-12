# Builds the SBL core app WITHOUT Gradle, using the local .tooling toolchain.
# Produces out\sbl.jar
$ErrorActionPreference = "Stop"
$repo   = Split-Path -Parent $MyInvocation.MyCommand.Path
$tool   = Join-Path $repo ".tooling"
$java   = Join-Path $tool "py\jdk4py\java-runtime\bin\java.exe"
$klib   = Join-Path $tool "kotlin\lib"
$libs   = Join-Path $tool "libs"
$out    = Join-Path $repo "out"
New-Item -ItemType Directory -Force -Path $out | Out-Null

if (-not (Test-Path $java)) { throw "JDK not found at $java. Run scripts\setup-toolchain.ps1 first." }

$compilerCp = (Get-ChildItem "$klib\*.jar" | ForEach-Object { $_.FullName }) -join ";"
$appDeps    = "$klib\kotlin-stdlib.jar;$libs\sqlite-jdbc.jar;$libs\slf4j-api.jar"
$src        = Join-Path $repo "src\main\kotlin"
$jar        = Join-Path $out "sbl.jar"

Write-Host "Compiling main sources ($src) ..." -ForegroundColor Cyan
& $java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -jvm-target 21 `
    -classpath $appDeps `
    -d $jar `
    $src
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed (exit $LASTEXITCODE)" }
Write-Host "Built $jar" -ForegroundColor Green
