# Compiles main + test sources and runs the self-contained assertion suite.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$tool = Join-Path $repo ".tooling"
$java = Join-Path $tool "py\jdk4py\java-runtime\bin\java.exe"
$klib = Join-Path $tool "kotlin\lib"
$libs = Join-Path $tool "libs"
$out  = Join-Path $repo "out"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$compilerCp = (Get-ChildItem "$klib\*.jar" | ForEach-Object { $_.FullName }) -join ";"
$appDeps    = "$klib\kotlin-stdlib.jar;$libs\sqlite-jdbc.jar;$libs\slf4j-api.jar"
$testJar    = Join-Path $out "sbl-test.jar"

Write-Host "Compiling main + test sources ..." -ForegroundColor Cyan
& $java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -jvm-target 21 `
    -classpath $appDeps `
    -d $testJar `
    (Join-Path $repo "src\main\kotlin") (Join-Path $repo "src\test\kotlin")
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed (exit $LASTEXITCODE)" }

$rcp = "$testJar;$klib\kotlin-stdlib.jar;$klib\kotlin-reflect.jar;$libs\sqlite-jdbc.jar;$libs\postgresql.jar;$libs\slf4j-api.jar;$libs\slf4j-simple.jar"
Write-Host "Running SBL test suite ..." -ForegroundColor Cyan
$jargs = @("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", $rcp, "com.piscessoft.sbl.SblTests")
& $java $jargs
exit $LASTEXITCODE
