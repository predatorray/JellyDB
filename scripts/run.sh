#!/usr/bin/env bash
set -euo pipefail
runtime="${JELLY_RUNTIME:-/opt/jellydb/current}"
export JELLY_BOOT_ID="$(cat /proc/sys/kernel/random/boot_id)"
exec java -Xms32m -Xmx128m -XX:MaxDirectMemorySize=32m -Xss256k -Dio.netty.allocator.numDirectArenas=1 -Dio.netty.allocator.numHeapArenas=1 -cp "$runtime/*:$runtime/lib/*" dev.jellydb.Main
