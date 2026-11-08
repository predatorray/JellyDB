#!/usr/bin/env bash
set -euo pipefail
# Modest one-shot host evidence; call from a timer or load-test supervisor, privately.
date -u +%FT%TZ
free -b
df -B1 /var/lib/jellydb
df -i /var/lib/jellydb
ps -eo pid,comm,rss,nlwp
for endpoint in http://127.0.0.1:9710/metrics http://127.0.0.1:8080/readyz; do
  curl --max-time 3 -fsS "$endpoint" || true
done
/opt/jellydb/bin/archive.py status
