#!/usr/bin/env bash
set -euo pipefail
# Usage: diagnostic-bundle.sh RETRIEVED_ARCHIVE_DIR OUTPUT.tar.gz
# No config files are copied: gather an explicit redacted configuration inventory manually.
source_dir="$(realpath "$1")"
output="$(realpath -m "$2")"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp "$source_dir/retrieval.json" "$source_dir/events.jsonl" "$stage/"
java -version > "$stage/java-version.txt" 2>&1
uname -a > "$stage/kernel.txt"
free -b > "$stage/memory.txt"
df -B1 > "$stage/disk.txt"
df -i > "$stage/inodes.txt"
ps -eo comm,pid,rss,nlwp > "$stage/process-resources.txt"
if [[ -f /opt/jellydb/current/SHA256SUMS ]]; then cp /opt/jellydb/current/SHA256SUMS "$stage/"; fi
cat > "$stage/README.txt" <<'TXT'
Review before sharing. Journal may contain upstream raw keys or operator messages.
No object payloads, environment credentials or raw configs were deliberately collected.
Attach an explicitly redacted config inventory and request IDs/time/partition/ledger evidence.
Cross-host clocks and missing wire trace IDs limit exact causality. Values cannot be reconstructed.
TXT
tar -czf "$output" -C "$stage" .
