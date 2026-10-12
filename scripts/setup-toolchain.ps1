# Sets up a local JVM + Kotlin toolchain into .tooling/ using the XYZ internal
# Artifactory (reachable without proxy) plus the corporate CA certificate.
# Only needed on machines without a system JDK/Gradle. Idempotent.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$tool = Join-Path $repo ".tooling"
$klib = Join-Path $tool "kotlin\lib"
$libs = Join-Path $tool "libs"
New-Item -ItemType Directory -Force -Path $klib, $libs, (Join-Path $tool "py") | Out-Null

$ca  = "C:\ProgramData\Certificates\ca.crt"
$m   = "https://repository.saas.cagip.group.gca/artifactory/maven-remote"
$py  = "C:\Program64\Python314\python.exe"
$kv  = "2.0.21"

function Get-Jar($url, $dest) {
    if (Test-Path $dest) { Write-Host "  cached $(Split-Path $dest -Leaf)"; return }
    $code = & curl.exe -s --ssl-no-revoke --cacert $ca --max-time 180 -o $dest $url -w "%{http_code}"
    if ($code -ne "200") { throw "Download failed ($code): $url" }
    Write-Host "  got    $(Split-Path $dest -Leaf) ($((Get-Item $dest).Length) bytes)"
}

Write-Host "1) JDK 21 (jdk4py) via internal PyPI ..." -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $tool "py\jdk4py\java-runtime\bin\java.exe"))) {
    & $py -m pip install --no-input --disable-pip-version-check "jdk4py==21.0.8.2" --target (Join-Path $tool "py")
} else { Write-Host "  cached jdk4py" }

Write-Host "2) Kotlin $kv compiler jars ..." -ForegroundColor Cyan
Get-Jar "$m/org/jetbrains/kotlin/kotlin-compiler/$kv/kotlin-compiler-$kv.jar"                   "$klib\kotlin-compiler.jar"
Get-Jar "$m/org/jetbrains/kotlin/kotlin-stdlib/$kv/kotlin-stdlib-$kv.jar"                       "$klib\kotlin-stdlib.jar"
Get-Jar "$m/org/jetbrains/kotlin/kotlin-reflect/$kv/kotlin-reflect-$kv.jar"                     "$klib\kotlin-reflect.jar"
Get-Jar "$m/org/jetbrains/kotlin/kotlin-script-runtime/$kv/kotlin-script-runtime-$kv.jar"       "$klib\kotlin-script-runtime.jar"
Get-Jar "$m/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar" "$klib\kotlinx-coroutines-core-jvm.jar"
Get-Jar "$m/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"          "$klib\trove4j.jar"
Get-Jar "$m/org/jetbrains/annotations/13.0/annotations-13.0.jar"                                "$klib\annotations.jar"

Write-Host "3) Runtime libs ..." -ForegroundColor Cyan
Get-Jar "$m/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar" "$libs\sqlite-jdbc.jar"
Get-Jar "$m/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar"    "$libs\postgresql.jar"
Get-Jar "$m/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"          "$libs\slf4j-api.jar"
Get-Jar "$m/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar"    "$libs\slf4j-simple.jar"

Write-Host "Toolchain ready in .tooling/" -ForegroundColor Green
