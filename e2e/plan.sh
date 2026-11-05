#!/usr/bin/env bash
# Plans the E2E matrix: one job per leg and example project, with the JDK and the logback, Jackson and slf4j versions
# the leg tests. Prints the matrix as a JSON array, the include list of the test job in e2e.yml.
#   floor   the JDK pom.xml compiles for (maven.compiler.source), logback-classic and jackson-databind as pom.xml pins
#           them, and the slf4j-api that logback release was built with (slf4j 2 does not find logback 1.2 and logs
#           nothing)
#   latest  the newest GA JDK (Adoptium's most_recent_feature_release) and the newest releases of logback-classic,
#           jackson-databind and slf4j-api on Maven Central
#   canary  the next JDK as an early-access build (Adoptium's tip_version), and a pre-release of each library when
#           one is newer than its newest release
# Env: LEG (all, floor, latest or canary), TOOL (all, maven or gradle), CLIENT_FROM (checkout or central), and
#      JDK_VERSION, LOGBACK_VERSION, JACKSON_VERSION: exact versions that replace the planned ones in every leg. An
#      exact logback version brings the slf4j-api it was built with.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CENTRAL=https://repo1.maven.org/maven2
UNQUALIFIED='^[0-9]+(\.[0-9]+)*$'

# The version pom.xml pins for the dependency with artifactId $1, and the value of property $1.
pom_pin() {
  awk -v artifact="<artifactId>$1</artifactId>" 'index($0, artifact) { found = 1; next }
    found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' "$ROOT/pom.xml"
}
pom_property() { sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$ROOT/pom.xml" | head -n 1; }

# Every version of group:artifact $1 on Maven Central, one per line. Not from search.maven.org: its index lags months
# behind Maven Central and still names logback 1.5.18 and logback-logtail 0.3.4 as the newest.
versions() {
  curl -fsS -m 60 "$CENTRAL/$(tr . / <<<"${1%:*}")/${1#*:}/maven-metadata.xml" | sed -n 's:.*<version>\(.*\)</version>.*:\1:p'
}

# Whether version $1 sorts above version $2.
newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" = "$1" ]; }

# The newest release among the versions on stdin: the highest one without a qualifier such as -alpha1 or -rc1.
# maven-metadata.xml's own <release> is whichever version was deployed last, which can be a pre-release (slf4j-api
# 2.1.0-alpha1) or a patch of an older line.
newest_release() { grep -E "$UNQUALIFIED" | sort -V | tail -n 1; }

# The newest pre-release above the newest release among the versions on stdin, or that release when there is none.
newest_prerelease() {
  local all release candidate pick=""
  all=$(cat)
  release=$(newest_release <<<"$all")
  while read -r candidate; do
    # 2.1.0-alpha1 is a pre-release of 2.1.0: it counts when 2.1.0 is above the newest release.
    if newer "${candidate%%-*}" "$release" && { [ -z "$pick" ] || newer "$candidate" "$pick"; }; then pick=$candidate; fi
  done < <(grep -vE "$UNQUALIFIED" <<<"$all")
  echo "${pick:-$release}"
}

# The example declares jackson-databind and jackson-datatype-jsr310 at one version, so only versions both have count.
jackson_versions() {
  comm -12 <(versions com.fasterxml.jackson.core:jackson-databind | sort) <(versions com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | sort)
}

# The slf4j-api version logback release $1 was built with.
slf4j_of_logback() {
  curl -fsS -m 60 "$CENTRAL/ch/qos/logback/logback-parent/$1/logback-parent-$1.pom" | sed -n 's:.*<slf4j.version>\(.*\)</slf4j.version>.*:\1:p' | head -n 1
}

ADOPTIUM=$(curl -fsS -m 60 https://api.adoptium.net/v3/info/available_releases)
# The client the examples take: the checkout's version (mvn install puts it into the local Maven repository), or the
# newest release on Maven Central, which the examples must already pin.
if [ "${CLIENT_FROM:-checkout}" = central ]; then
  CLIENT=$(versions com.logtail:logback-logtail | newest_release)
else
  CLIENT=$(sed -n 's:^    <version>\(.*\)</version>$:\1:p' "$ROOT/pom.xml" | head -n 1)
fi

LEGS=$([ "${LEG:-all}" = all ] && echo "floor latest canary" || echo "$LEG")
TOOLS=$([ "${TOOL:-all}" = all ] && echo "maven gradle" || echo "$TOOL")
MATRIX='[]'
for leg in $LEGS; do
  case "$leg" in
    floor)
      jdk=$(pom_property maven.compiler.source)
      logback=$(pom_pin logback-classic)
      jackson=$(pom_pin jackson-databind)
      slf4j=$(slf4j_of_logback "$logback") ;;
    latest)
      jdk=$(jq -r .most_recent_feature_release <<<"$ADOPTIUM")
      logback=$(versions ch.qos.logback:logback-classic | newest_release)
      jackson=$(jackson_versions | newest_release)
      slf4j=$(versions org.slf4j:slf4j-api | newest_release) ;;
    canary)
      jdk=$(jq -r .tip_version <<<"$ADOPTIUM")-ea
      logback=$(versions ch.qos.logback:logback-classic | newest_prerelease)
      jackson=$(jackson_versions | newest_prerelease)
      slf4j=$(versions org.slf4j:slf4j-api | newest_prerelease) ;;
    *) echo "unknown leg $leg" >&2; exit 1 ;;
  esac
  jdk=${JDK_VERSION:-$jdk}
  jackson=${JACKSON_VERSION:-$jackson}
  if [ -n "${LOGBACK_VERSION:-}" ]; then
    logback=$LOGBACK_VERSION
    slf4j=$(slf4j_of_logback "$logback")
  fi
  for value in "$jdk" "$logback" "$jackson" "$slf4j" "$CLIENT"; do
    [ -n "$value" ] && [ "$value" != null ] || { echo "the $leg leg could not be planned: jdk '$jdk', logback '$logback', jackson '$jackson', slf4j '$slf4j', client '$CLIENT'" >&2; exit 1; }
  done
  for tool in $TOOLS; do
    MATRIX=$(jq -c --arg leg "$leg" --arg tool "$tool" --arg jdk "$jdk" --arg logback "$logback" --arg jackson "$jackson" \
      --arg slf4j "$slf4j" --arg source "${CLIENT_FROM:-checkout}" --arg client "$CLIENT" '. + [{
        name: "\($leg) (JDK \($jdk), \($tool), logback \($logback), jackson \($jackson))",
        leg: $leg, tool: $tool, jdk: $jdk,
        # setup-java exports each JDK home as JAVA_HOME_<major>_X64.
        jdk_major: ($jdk | sub("[.+-].*$"; "")),
        logback: $logback, jackson: $jackson, slf4j: $slf4j, source: $source, client: $client,
        # The floor runs on the oldest Ubuntu image GitHub offers, the other legs follow ubuntu-latest.
        runner: (if $leg == "floor" then "ubuntu-24.04" else "ubuntu-latest" end)
      }]' <<<"$MATRIX")
  done
done
echo "$MATRIX"
