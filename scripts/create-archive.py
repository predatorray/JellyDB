#!/usr/bin/env python3
"""Create a reproducible runtime tar.gz without GNU/BSD tar command-line differences."""

import argparse
from datetime import datetime, timezone
import gzip
import os
from pathlib import Path
import stat
import tarfile
import tempfile

# Match project.build.outputTimestamp; timestamps do not depend on the build host.
ARCHIVE_EPOCH = int(datetime(2026, 11, 7, tzinfo=timezone.utc).timestamp())


def create_archive(source, output):
    source = Path(source).resolve()
    output = Path(output).resolve()
    if not source.is_dir():
        raise ValueError("Archive source must be a directory")
    if source == output or source in output.parents:
        raise ValueError("Archive output must be outside its source directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=output.name + ".", delete=False) as raw:
            temporary = Path(raw.name)
            with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as archive:
                    paths = [source] + sorted(source.rglob("*"), key=lambda p: p.relative_to(source).as_posix())
                    for path in paths:
                        metadata = path.lstat()
                        if not (stat.S_ISDIR(metadata.st_mode) or stat.S_ISREG(metadata.st_mode)):
                            raise ValueError("Runtime archive only supports regular files and directories")
                        name = "." if path == source else "./" + path.relative_to(source).as_posix()
                        entry = tarfile.TarInfo(name)
                        entry.uid = entry.gid = 0
                        entry.uname = entry.gname = ""
                        entry.mtime = ARCHIVE_EPOCH
                        if stat.S_ISDIR(metadata.st_mode):
                            entry.type = tarfile.DIRTYPE
                            entry.mode = 0o755
                            archive.addfile(entry)
                        else:
                            entry.mode = 0o755 if metadata.st_mode & 0o111 else 0o644
                            entry.size = metadata.st_size
                            with path.open("rb") as content:
                                archive.addfile(entry, content)
            raw.flush()
            os.fsync(raw.fileno())
        os.replace(temporary, output)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    create_archive(args.source, args.output)


if __name__ == "__main__":
    main()
