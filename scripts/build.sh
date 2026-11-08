#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PYTHON:=python3}"
: "${MVN:=mvn}"
# A JDK 17+ and Maven 3.9+ must already be installed off-host. Configure Maven TLS/proxy normally.
read -r commit version < <("$PYTHON" -c 'import json; p=json.load(open("candybox.lock")); print(p["commit"],p["version"])')
checkout="$PWD/.upstream/candybox-${commit:0:7}"
if [[ ! -e "$checkout/.git" ]]; then
  mkdir -p .upstream
  git clone https://github.com/predatorray/candybox.git "$checkout"
fi
if [[ -n "$(git -C "$checkout" status --porcelain)" ]]; then
  # Subsequent builds may contain only our exact POM version substitutions.
  "$PYTHON" scripts/check-upstream.py "$checkout"
fi
git -C "$checkout" checkout --detach "$commit"
test "$(git -C "$checkout" rev-parse HEAD)" = "$commit"
"$MVN" -B -ntp -f "$checkout/pom.xml" org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion="$version" -DgenerateBackupPoms=false
"$PYTHON" scripts/check-upstream.py "$checkout"
"$MVN" -B -ntp -f "$checkout/pom.xml" -pl candybox-client,candybox-server -am install -DskipTests
"$MVN" -B -ntp clean verify
"$MVN" -B -ntp dependency:tree -DoutputFile=target/dependencies.txt
scripts/package.sh
