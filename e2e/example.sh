#!/usr/bin/env bash
# Builds or runs one of the example projects the way its README tells a customer to, on the leg's JDK and with the
# leg's logback, Jackson and slf4j versions. The build's output goes to out/<command>.log as well, and a failure ends
# with one ::error:: line giving the build's own reason.
#   e2e/example.sh classpath   compiles the example and writes its runtime classpath, one jar per line, to out/classpath.txt
#   e2e/example.sh run         runs the example, which sends its logs to BETTER_STACK_INGESTING_HOST with BETTER_STACK_SOURCE_TOKEN
# Env: TOOL (maven or gradle), JDK (major version, installed at JAVA_HOME_<JDK>_X64 as actions/setup-java exports it),
#      LOGBACK, JACKSON, SLF4J (versions), CLIENT_FROM (checkout: logback-logtail from the local Maven repository,
#      where mvn install put the checkout's build; central: from Maven Central).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/out
LOG=$OUT/$1.log
mkdir -p "$OUT"
JDK_HOME_VAR=JAVA_HOME_${JDK}_X64
JDK_HOME=${!JDK_HOME_VAR:-}
[ -n "$JDK_HOME" ] || { echo "::error::$JDK_HOME_VAR is not set, so JDK $JDK is not installed"; exit 1; }
WHAT="the $TOOL example on JDK $JDK with logback $LOGBACK, jackson $JACKSON and slf4j $SLF4J"

# The first error Maven reports, or what Gradle says went wrong with its causes on one line.
fail() {
  local reason
  reason=$(sed -n '/^\[ERROR\]/{p;q;}' "$LOG")
  [ -n "$reason" ] || reason=$(sed -n '/^\* What went wrong:/,/^$/p' "$LOG" | sed -n -e 2p -e '/^ *> /p' | sed 's/^ *//' | tr '\n' ' ')
  echo "::error::$1: ${reason:-see the log of this step}"
  exit 1
}

case "$TOOL" in
  maven)
    cd "$ROOT/examples/maven"
    # Maven itself runs on the leg's JDK. The example compiles for Java 11, the oldest logback 1.5 runs on, and for
    # Java 8 on JDK 8, as its README tells Java 8 users.
    export JAVA_HOME=$JDK_HOME
    ARGS=(-B -ntp -Dlogback.version="$LOGBACK" -Djackson.version="$JACKSON" -Dslf4j.version="$SLF4J")
    [ "$JDK" -ge 11 ] || ARGS+=(-Dmaven.compiler.source=8 -Dmaven.compiler.target=8)
    case "$1" in
      classpath)
        mvn "${ARGS[@]}" compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
          -DincludeScope=runtime -Dmdep.outputFile="$OUT/classpath.raw" 2>&1 | tee "$LOG" || fail "$WHAT does not build"
        tr ':' '\n' < "$OUT/classpath.raw" > "$OUT/classpath.txt" ;;
      run)
        mvn "${ARGS[@]}" compile -e exec:java -Dexec.mainClass="com.logtail.example.App" 2>&1 | tee "$LOG" || fail "$WHAT failed" ;;
    esac ;;
  gradle)
    cd "$ROOT/examples/gradle"
    # Gradle runs on JAVA_HOME and compiles and runs the example on the leg's JDK through a toolchain that
    # e2e/init.gradle sets up, so that build.gradle stays the plain project a customer copies. Only the JDK that
    # setup-java installed counts, Gradle neither looks for others nor downloads one. Deprecation warnings are
    # shown one by one: the example has to keep working on the next Gradle.
    ARGS=(--console=plain --warning-mode=all --init-script "$ROOT/e2e/init.gradle" -Pe2e.jdk="$JDK"
      -Porg.gradle.java.installations.fromEnv="$JDK_HOME_VAR" -Porg.gradle.java.installations.auto-detect=false
      -Porg.gradle.java.installations.auto-download=false
      -PlogbackVersion="$LOGBACK" -PjacksonVersion="$JACKSON" -Pslf4jVersion="$SLF4J")
    [ "$CLIENT_FROM" != checkout ] || ARGS+=(-Pe2e.mavenLocal)
    case "$1" in
      classpath)
        gradle "${ARGS[@]}" -Pe2e.classpathFile="$OUT/classpath.txt" classes e2eClasspath 2>&1 | tee "$LOG" || fail "$WHAT does not build" ;;
      run)
        gradle "${ARGS[@]}" run 2>&1 | tee "$LOG" || fail "$WHAT failed" ;;
    esac ;;
  *) echo "::error::unknown tool $TOOL"; exit 1 ;;
esac
