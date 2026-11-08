#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PYTHON:=python3}"
commit="$("$PYTHON" -c 'import json; print(json.load(open("candybox.lock"))["commit"])')"
"$PYTHON" - <<'PYCHECK'
import json,pathlib
pin=json.loads(pathlib.Path('candybox.lock').read_text())
jars=list(pathlib.Path('target/lib').glob('candybox-client-*.jar'))
assert len(jars)==1 and jars[0].name=='candybox-client-'+pin['version']+'.jar', 'Rebuild with clean verify for the exact pinned client'
PYCHECK
rm -rf -- target/runtime
mkdir -p target/runtime/bin target/runtime/conf target/runtime/share/examples target/runtime/share/docs
cp target/jellydb-0.1.0-SNAPSHOT.jar target/runtime/
cp -r target/lib target/runtime/
cp target/dependencies.txt target/runtime/
cp scripts/run.sh scripts/diagnostic-bundle.sh scripts/measure.sh tools/archive.py ops/archive-send ops/cluster-component target/runtime/bin/
cp ops/*.service ops/*.timer ops/*.example ops/*.conf ops/*.cfg ops/*.properties ops/*.xml ops/Caddyfile target/runtime/conf/
cp examples/* target/runtime/share/examples/
cp docs/operations.md docs/upstream-blocker.md target/runtime/share/docs/
printf '%s\n' "$commit" > target/runtime/CANDYBOX_COMMIT
"$PYTHON" - <<'PYHASH'
import hashlib, pathlib
root=pathlib.Path('target/runtime')
files=sorted(p for p in root.rglob('*') if p.is_file() and p.name!='SHA256SUMS')
(root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+str(p.relative_to(root))+'\n' for p in files))
PYHASH
"$PYTHON" scripts/create-archive.py target/runtime target/jellydb-runtime.tar.gz
