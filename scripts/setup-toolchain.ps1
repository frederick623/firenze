# Sets up a local JVM + Kotlin toolchain into .tooling/ using the XYZ internal
# Artifactory plus the corporate CA certificate.
# Only needed on machines without a system JDK/Gradle. Idempotent.
#
# Settings (parameter > environment variable > system default):
#   -ArtHost  / SBL_ART_HOST  Artifactory host (default: repository.saas.cagip.group.gca)
#   -CaCert   / SBL_CA_CERT   CA certificate file (default: C:\ProgramData\Certificates\ca.crt
#                             if present, otherwise the Windows trust store)
#   -Python   / SBL_PYTHON    Python interpreter (default: C:\Program64\Python314\python.exe
#                             if present, otherwise python / python3 / py on PATH)
#
# Proxy handling:
#   By default the internal Artifactory host is reached DIRECTLY, even when
#   HTTPS_PROXY/HTTP_PROXY are set: the corporate proxy re-signs TLS with a
#   certificate pip does not trust ("self-signed certificate in chain").
#   If the host is not directly reachable (e.g. off-network), force a proxy:
#     -Proxy http://proxy.host:8080   (or env SBL_PROXY)
#     -ProxyNtlm                      authenticate with your Windows login (curl only)
param(
    [string]$ArtHost = $env:SBL_ART_HOST,
    [string]$CaCert  = $env:SBL_CA_CERT,
    [string]$Python  = $env:SBL_PYTHON,
    [string]$Proxy   = $env:SBL_PROXY,
    [switch]$ProxyNtlm
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$tool = Join-Path $repo ".tooling"
$klib = Join-Path $tool "kotlin\lib"
$libs = Join-Path $tool "libs"
New-Item -ItemType Directory -Force -Path $klib, $libs, (Join-Path $tool "py") | Out-Null

if (-not $ArtHost) { $ArtHost = "repository.saas.cagip.group.gca" }

if ($CaCert) {
    if (-not (Test-Path $CaCert -PathType Leaf)) { throw "CA certificate not found: $CaCert" }
} elseif (Test-Path "C:\ProgramData\Certificates\ca.crt" -PathType Leaf) {
    $CaCert = "C:\ProgramData\Certificates\ca.crt"
}

if ($Python) {
    $py = (Get-Command $Python -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $py) { throw "Python not found: $Python" }
} else {
    # Skip the Microsoft Store "python" stubs, which are not real interpreters.
    $py = @("C:\Program64\Python314\python.exe", "python", "python3", "py") |
        ForEach-Object { Get-Command $_ -CommandType Application -ErrorAction SilentlyContinue } |
        Where-Object { $_.Source -notlike "*\WindowsApps\*" } |
        Select-Object -First 1 -ExpandProperty Source
    if (-not $py) { throw "Python not found on this system. Pass -Python <path> or set SBL_PYTHON." }
}

$m  = "https://$ArtHost/artifactory/maven-remote"
$kv = "2.0.21"

Write-Host "Artifactory : $ArtHost"
Write-Host "CA cert     : $(if ($CaCert) { $CaCert } else { 'Windows trust store' })"
Write-Host "Python      : $py"

$curlOpts = @("-s", "--ssl-no-revoke", "--max-time", "180")
$pipOpts  = @()
if ($CaCert) {
    $curlOpts += @("--cacert", $CaCert)
    $pipOpts  += @("--cert", $CaCert)
}
if ($Proxy) {
    Write-Host "Using proxy $($Proxy -replace '//[^@/]+@', '//***@')" -ForegroundColor Yellow
    $curlOpts += @("--proxy", $Proxy)
    if ($ProxyNtlm) { $curlOpts += @("--proxy-ntlm", "--proxy-user", ":") }
    $pipOpts  += @("--proxy", $Proxy)
} else {
    Write-Host "Bypassing proxy for $ArtHost" -ForegroundColor Yellow
    $curlOpts += @("--noproxy", $ArtHost)
}

function Get-Jar($url, $dest) {
    if (Test-Path $dest) { Write-Host "  cached $(Split-Path $dest -Leaf)"; return }
    $code = & curl.exe @curlOpts -o $dest $url -w "%{http_code}"
    if ($code -ne "200") {
        Remove-Item $dest -Force -ErrorAction SilentlyContinue
        throw "Download failed ($code): $url"
    }
    Write-Host "  got    $(Split-Path $dest -Leaf) ($((Get-Item $dest).Length) bytes)"
}

Write-Host "1) JDK 21 (jdk4py) via internal PyPI ..." -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $tool "py\jdk4py\java-runtime\bin\java.exe"))) {
    $prevNoProxy = $env:NO_PROXY
    if (-not $Proxy) { $env:NO_PROXY = (@($prevNoProxy, $ArtHost) | Where-Object { $_ }) -join "," }
    try {
        & $py -m pip install --no-input --disable-pip-version-check @pipOpts "jdk4py==21.0.8.2" --target (Join-Path $tool "py")
    } finally { $env:NO_PROXY = $prevNoProxy }
    if ($LASTEXITCODE -ne 0) { throw "pip install jdk4py failed (exit $LASTEXITCODE)" }
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
