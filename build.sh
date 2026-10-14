#!/usr/bin/env bash
# Builds the SBL core app WITHOUT Gradle, using the local .tooling toolchain.
# Produces out/sbl.jar
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOL="$REPO/.tooling"
KLIB="$TOOL/kotlin/lib"
LIBS="$TOOL/libs"
OUT="$REPO/out"
mkdir -p "$OUT"

# jdk4py lays the runtime out as java-runtime/bin/java on Linux and
# java-runtime/Contents/Home/bin/java on macOS; find covers both.
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
SRC="$REPO/src/main/kotlin"
JAR="$OUT/sbl.jar"

echo "Compiling main sources ($SRC) ..."
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 21 \
    -classpath "$APP_DEPS" \
    -d "$JAR" \
    "$SRC"
echo "Built $JAR"
