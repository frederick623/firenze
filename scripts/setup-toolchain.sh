#!/usr/bin/env bash
# Sets up a local JVM + Kotlin toolchain into .tooling/ using the XYZ internal
# Artifactory plus the corporate CA certificate.
# Only needed on machines without a system JDK/Gradle. Idempotent.
#
# Environment overrides:
#   SBL_CA_CERT  path to the corporate CA certificate. If unset, curl uses the
#                system trust store (set this on locked-down corporate machines).
#   SBL_PYTHON   python interpreter used to fetch jdk4py (default: python3).
#   SBL_ART_HOST Artifactory host (default: repository.saas.cagip.group.gca).
#   SBL_PROXY    force a proxy for the internal Artifactory, e.g.
#                http://user:pass@proxy.host:8080. By default the Artifactory
#                host is reached directly even if HTTPS_PROXY is set, because
#                the corporate proxy re-signs TLS and pip rejects it.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL="$REPO/.tooling"
KLIB="$TOOL/kotlin/lib"
LIBS="$TOOL/libs"
mkdir -p "$KLIB" "$LIBS" "$TOOL/py"

CA="${SBL_CA_CERT:-}"
ART_HOST="${SBL_ART_HOST:-repository.saas.cagip.group.gca}"
M="https://$ART_HOST/artifactory/maven-remote"
PY="${SBL_PYTHON:-python3}"
KV="2.0.21"

PROXY="${SBL_PROXY:-}"

CURL_OPTS=(-s --max-time 180)
PIP_OPTS=()
if [ -n "$CA" ]; then
  CURL_OPTS+=(--cacert "$CA")
  PIP_OPTS+=(--cert "$CA")
fi
if [ -n "$PROXY" ]; then
  echo "Using proxy $(echo "$PROXY" | sed -E 's#//[^@/]+@#//***@#')"
  CURL_OPTS+=(--proxy "$PROXY")
  PIP_OPTS+=(--proxy "$PROXY")
else
  echo "Bypassing proxy for $ART_HOST"
  CURL_OPTS+=(--noproxy "$ART_HOST")
  export NO_PROXY="${NO_PROXY:+$NO_PROXY,}$ART_HOST" no_proxy="${no_proxy:+$no_proxy,}$ART_HOST"
fi

get_jar() {
  local url="$1" dest="$2" code
  if [ -f "$dest" ]; then
    echo "  cached $(basename "$dest")"
    return
  fi
  code="$(curl "${CURL_OPTS[@]}" -o "$dest" "$url" -w "%{http_code}")"
  if [ "$code" != "200" ]; then
    rm -f "$dest"
    echo "Download failed ($code): $url" >&2
    exit 1
  fi
  echo "  got    $(basename "$dest") ($(wc -c < "$dest" | tr -d ' ') bytes)"
}

echo "1) JDK 21 (jdk4py) via internal PyPI ..."
if [ ! -e "$TOOL/py/jdk4py/java-runtime/bin/java" ] && \
   ! find "$TOOL/py/jdk4py" -type f -name java >/dev/null 2>&1; then
  "$PY" -m pip install --no-input --disable-pip-version-check ${PIP_OPTS[@]+"${PIP_OPTS[@]}"} "jdk4py==21.0.8.2" --target "$TOOL/py"
else
  echo "  cached jdk4py"
fi

echo "2) Kotlin $KV compiler jars ..."
get_jar "$M/org/jetbrains/kotlin/kotlin-compiler/$KV/kotlin-compiler-$KV.jar"             "$KLIB/kotlin-compiler.jar"
get_jar "$M/org/jetbrains/kotlin/kotlin-stdlib/$KV/kotlin-stdlib-$KV.jar"                 "$KLIB/kotlin-stdlib.jar"
get_jar "$M/org/jetbrains/kotlin/kotlin-reflect/$KV/kotlin-reflect-$KV.jar"               "$KLIB/kotlin-reflect.jar"
get_jar "$M/org/jetbrains/kotlin/kotlin-script-runtime/$KV/kotlin-script-runtime-$KV.jar" "$KLIB/kotlin-script-runtime.jar"
get_jar "$M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar" "$KLIB/kotlinx-coroutines-core-jvm.jar"
get_jar "$M/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"    "$KLIB/trove4j.jar"
get_jar "$M/org/jetbrains/annotations/13.0/annotations-13.0.jar"                          "$KLIB/annotations.jar"

echo "3) Runtime libs ..."
get_jar "$M/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar" "$LIBS/sqlite-jdbc.jar"
get_jar "$M/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar"    "$LIBS/postgresql.jar"
get_jar "$M/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"          "$LIBS/slf4j-api.jar"
get_jar "$M/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar"    "$LIBS/slf4j-simple.jar"

echo "Toolchain ready in .tooling/"
