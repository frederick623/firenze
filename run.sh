#!/usr/bin/env bash
# Starts the SBL core (no dummy data; see seed.sh). Builds first if out/sbl.jar is missing.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOL="$REPO/.tooling"
KLIB="$TOOL/kotlin/lib"
LIBS="$TOOL/libs"
OUT="$REPO/out"
JAR="$OUT/sbl.jar"

JAVA="$TOOL/py/jdk4py/java-runtime/bin/java"
if [ ! -x "$JAVA" ]; then
  JAVA="$(find "$TOOL/py/jdk4py" -type f -name java 2>/dev/null | head -n1 || true)"
fi
if [ -z "${JAVA:-}" ] || [ ! -x "$JAVA" ]; then
  echo "JDK not found under $TOOL/py/jdk4py. Run scripts/setup-toolchain.sh first." >&2
  exit 1
fi

if [ ! -f "$JAR" ]; then
  "$REPO/build.sh"
fi

RCP="$JAR:$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-reflect.jar:$LIBS/sqlite-jdbc.jar:$LIBS/postgresql.jar:$LIBS/slf4j-api.jar:$LIBS/slf4j-simple.jar"
exec "$JAVA" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$RCP" com.piscessoft.sbl.app.MainKt "$@"
