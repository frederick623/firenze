# Seeds dummy/demo data into the SBL database. Builds first if out\sbl.jar is missing.
# Usage: .\seed.ps1 [--scenario] [--db-connector ..] [--db-url ..] [--db-user ..] [--db-password ..]
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$tool = Join-Path $repo ".tooling"
$java = Join-Path $tool "py\jdk4py\java-runtime\bin\java.exe"
$klib = Join-Path $tool "kotlin\lib"
$libs = Join-Path $tool "libs"
$out  = Join-Path $repo "out"
$jar  = Join-Path $out "sbl.jar"

if (-not (Test-Path $jar)) { & (Join-Path $repo "build.ps1") }

$rcp = "$jar;$klib\kotlin-stdlib.jar;$klib\kotlin-reflect.jar;$libs\sqlite-jdbc.jar;$libs\postgresql.jar;$libs\slf4j-api.jar;$libs\slf4j-simple.jar"
$jargs = @("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", $rcp, "com.piscessoft.sbl.seed.DemoDataSeederKt") + $args
& $java $jargs
exit $LASTEXITCODE
