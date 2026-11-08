#!/usr/bin/env python3
"""Bundle tested HTTP runtime outputs into versioned Linux VPS release assets."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def validate_tag(tag, version):
    if not re.fullmatch(r'v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?', tag):
        raise ValueError('Use a version tag such as v0.1.0 or v0.1.0-rc.1')
    if tag[1:].split('-', 1)[0] != version.removesuffix('-SNAPSHOT'):
        raise ValueError('Tag base version must match pom.xml version')
    return tag.startswith('v0.') or '-' in tag


def checksums(root):
    files = sorted(p for p in root.rglob('*') if p.is_file() and p != root / 'SHA256SUMS')
    (root / 'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.relative_to(root).as_posix() + '\n' for p in files))


def bundle(tag, require_clean=False):
    version = ET.parse(ROOT / 'pom.xml').findtext('{*}version')
    prerelease = validate_tag(tag, version)
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'amd64'):
        raise ValueError('Build Linux amd64 releases on a Linux amd64 runner')
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
    if require_clean and dirty: raise ValueError('Publishing requires a clean source checkout')
    runtime = ROOT / 'target/runtime'
    if not (runtime / 'SHA256SUMS').exists(): raise ValueError('Run make build first')
    # Verify existing outputs before adding release metadata.
    installer_spec = importlib.util.spec_from_file_location('install_http', ROOT / 'scripts/install-http.py')
    installer = importlib.util.module_from_spec(installer_spec); installer_spec.loader.exec_module(installer)
    installer.verify(runtime)
    pin = json.loads((ROOT / 'candybox.lock').read_text())
    if (runtime / 'CANDYBOX_COMMIT').read_text().strip() != pin['commit']:
        raise ValueError('Runtime and source dependency pin differ')
    root = ROOT / 'target/release'
    if root.exists(): shutil.rmtree(root)
    root.mkdir()
    distribution = root / ('jellydb-' + tag + '-linux-amd64')
    shutil.copytree(runtime, distribution)
    shutil.copyfile(ROOT / 'scripts/install-http.py', distribution / 'bin/install-http.py')
    shutil.copyfile(ROOT / 'LICENSE', distribution / 'LICENSE')
    shutil.copyfile(ROOT / 'docs/releases.md', distribution / 'share/docs/releases.md')
    metadata = {'tag': tag, 'sourceCommit': revision, 'dirtySource': dirty,
                'platform': 'linux-amd64', 'javaMinimum': 17, 'javaBundled': False,
                'candyboxCommit': pin['commit'], 'mavenVersion': version,
                'prerelease': prerelease, 'scope': 'HTTP service only; private storage cluster required',
                'archive': distribution.name + '.tar.gz'}
    (distribution / 'release.json').write_text(json.dumps(metadata, sort_keys=True, indent=2) + '\n')
    checksums(distribution)
    archive_spec = importlib.util.spec_from_file_location('archive', ROOT / 'scripts/create-archive.py')
    archive = importlib.util.module_from_spec(archive_spec); archive_spec.loader.exec_module(archive)
    assets = ROOT / 'target/dist'
    if assets.exists(): shutil.rmtree(assets)
    assets.mkdir()
    archive.create_archive(distribution, assets / metadata['archive'])
    shutil.copyfile(distribution / 'release.json', assets / 'release.json')
    shutil.copyfile(runtime / 'dependencies.txt', assets / 'dependencies.txt')
    shutil.copyfile(ROOT / 'docs/releases.md', assets / 'DEPLOY.md')
    (assets / 'release-notes.md').write_text(
        'JellyDB ' + tag + '\n\nSource: `' + revision + '`; Candybox: `' + pin['commit'] + '`.\n\n'
        'Linux amd64 HTTP runtime with systemd installer. Requires Java 17+, Python 3.9+, '
        'and a separately configured private Candybox/ZooKeeper/BookKeeper cluster. '
        'The Java runtime and storage cluster are not bundled.\n\n'
        'Verify SHA256SUMS, extract the tarball, then run `sudo python3 bin/install-http.py`. '
        'The installer preserves configuration and does not start services. See DEPLOY.md.\n\n'
        'Experimental: three-VM sizing, physical reclamation, public HTTPS and the 24-hour soak '
        'remain deployment gates; no production availability guarantee is claimed.\n')
    checksums(assets)
    print(assets)
    return assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--require-clean', action='store_true')
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    if args.validate_only: validate_tag(args.tag, ET.parse(ROOT / 'pom.xml').findtext('{*}version'))
    else: bundle(args.tag, args.require_clean)


if __name__ == '__main__': main()
