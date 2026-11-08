#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${MVN:=mvn}"
# Test-only real storage: temporary ZooKeeper, three bookies, one Candybox owner.
# Run scripts/build.sh once first to install the exact pinned upstream dependencies.
"$MVN" -B -ntp test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
exec java -XX:ActiveProcessorCount=2 -Xmx768m \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
  -cp "target/classes:target/test-classes:$(cat target/test-classpath.txt)" \
  dev.jellydb.NativeDemoMain "${1:-8080}"
