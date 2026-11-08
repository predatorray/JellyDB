#!/usr/bin/env python3
"""Install a verified JellyDB HTTP release. Does not start services or install the storage cluster."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def verify(root):
    expected = set()
    for line in (root / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        path = Path(name)
        if not re.fullmatch('[a-f0-9]{64}', digest) or path.is_absolute() or '..' in path.parts or name in expected:
            raise ValueError('Invalid checksum inventory')
        expected.add(name)
        if hashlib.sha256((root / path).read_bytes()).hexdigest() != digest:
            raise ValueError('Checksum mismatch: ' + name)
    actual = set()
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ValueError('Release must not contain symlinks')
        if path.is_file() and path != root / 'SHA256SUMS':
            actual.add(path.relative_to(root).as_posix())
    if actual != expected:
        raise ValueError('Release file inventory mismatch')


def install(source, stage=None):
    source = source.resolve()
    # Reject symlinks before reading any paths from the inventory.
    if any(p.is_symlink() for p in source.rglob('*')):
        raise ValueError('Release must not contain symlinks')
    verify(source)
    metadata = json.loads((source / 'release.json').read_text())
    version = metadata['tag']
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid release version')
    if metadata.get('platform') != 'linux-amd64':
        raise ValueError('Unsupported release platform')
    if stage is None:
        if sys.platform != 'linux' or os.uname().machine not in ('x86_64', 'amd64'):
            raise ValueError('This release requires Linux amd64')
        if os.geteuid() != 0:
            raise PermissionError('Run with sudo, or use --stage DIR to inspect installation')
        java = subprocess.run(['java', '-XshowSettings:properties', '-version'], check=True, capture_output=True, text=True)
        specification = re.search(r'java.specification.version\s*=\s*([0-9]+)', java.stdout + java.stderr)
        if specification is None or int(specification[1]) < 17:
            raise ValueError('Install a Java 17+ runtime before installation')
        if subprocess.run(['id', '-u', 'jellydb'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            subprocess.run(['useradd', '--system', '--home', '/var/lib/jellydb', '--shell', '/usr/sbin/nologin', 'jellydb'], check=True)
    base = Path('/') if stage is None else Path(stage).resolve()
    prefix = base / 'opt/jellydb'
    releases = prefix / 'releases'
    releases.mkdir(parents=True, exist_ok=True)
    prefix.chmod(0o755); releases.chmod(0o755)
    destination = releases / version
    if destination.exists():
        verify(destination)
        if ((destination / 'release.json').read_bytes() != (source / 'release.json').read_bytes()
                or (destination / 'SHA256SUMS').read_bytes() != (source / 'SHA256SUMS').read_bytes()):
            raise ValueError('Existing release identity differs; refusing overwrite')
    else:
        temporary = Path(tempfile.mkdtemp(prefix='.install-', dir=releases))
        try:
            shutil.copytree(source, temporary, dirs_exist_ok=True)
            verify(temporary)
            temporary.rename(destination)
        finally:
            if temporary.exists(): shutil.rmtree(temporary)
    config = base / 'etc/jellydb'
    config.mkdir(parents=True, exist_ok=True, mode=0o750)
    env = config / 'http.env'
    if not env.exists():
        # Exclusive create: upgrades never replace operator configuration.
        with env.open('xb') as out: out.write((destination / 'conf/http.env.example').read_bytes())
        env.chmod(0o600)
    data = base / 'var/lib/jellydb'
    data.mkdir(parents=True, exist_ok=True, mode=0o750)
    if stage is None: shutil.chown(data, user='jellydb', group='jellydb')
    unit = base / 'etc/systemd/system/jellydb.service'
    unit.parent.mkdir(parents=True, exist_ok=True)
    if not unit.exists(): shutil.copyfile(destination / 'conf/jellydb.service', unit)
    current = prefix / 'current'
    if current.exists() and not current.is_symlink():
        raise ValueError('current must be a symlink; refusing to replace user data')
    link = prefix / ('.current-' + str(os.getpid()))
    try:
        link.symlink_to('releases/' + version)
        os.replace(link, current)
    finally:
        if link.is_symlink(): link.unlink()
    (prefix / 'bin').mkdir(exist_ok=True)
    (prefix / 'bin').chmod(0o755)
    launcher = prefix / 'bin/run.sh'
    if not launcher.exists() and not launcher.is_symlink(): launcher.symlink_to('../current/bin/run.sh')
    if stage is None: subprocess.run(['systemctl', 'daemon-reload'], check=True)
    print('Installed HTTP release ' + version + '. Existing configuration and unit overrides preserved.')
    print('Configure /etc/jellydb/http.env for your private Candybox cluster, then:')
    print('  sudo systemctl enable --now jellydb')
    print('  curl -fsS http://127.0.0.1:8080/readyz')
    print('On upgrade, drain traffic and restart jellydb explicitly. No services were started.')
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, help='Write only into this directory; no root, user or systemd changes')
    args = parser.parse_args()
    install(Path(__file__).resolve().parents[1], args.stage)


if __name__ == '__main__': main()
