#!/usr/bin/env bash
# Compiles main + test sources and runs the self-contained assertion suite.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOL="$REPO/.tooling"
KLIB="$TOOL/kotlin/lib"
LIBS="$TOOL/libs"
OUT="$REPO/out"
mkdir -p "$OUT"

JAVA="$TOOL/py/jdk4py/java-runtime/bin/java"
if [ ! -x "$JAVA" ]; then
  JAVA="$(find "$TOOL/py/jdk4py" -type f -name java 2>/dev/null | head -n1 || true)"
fi
if [ -z "${JAVA:-}" ] || [ ! -x "$JAVA" ]; then
  echo "JDK not found under $TOOL/py/jdk4py. Run scripts/setup-toolchain.sh first." >&2
  exit 1
fi

COMPILER_CP="$(printf '%s:' "$KLIB"/*.jar)"; COMPILER_CP="${COMPILER_CP%:}"
APP_DEPS="$KLIB/kotlin-stdlib.jar:$LIBS/sqlite-jdbc.jar:$LIBS/slf4j-api.jar"
TEST_JAR="$OUT/sbl-test.jar"

echo "Compiling main + test sources ..."
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 21 \
    -classpath "$APP_DEPS" \
    -d "$TEST_JAR" \
    "$REPO/src/main/kotlin" "$REPO/src/test/kotlin"

RCP="$TEST_JAR:$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-reflect.jar:$LIBS/sqlite-jdbc.jar:$LIBS/postgresql.jar:$LIBS/slf4j-api.jar:$LIBS/slf4j-simple.jar"
echo "Running SBL test suite ..."
exec "$JAVA" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$RCP" com.piscessoft.sbl.SblTests
