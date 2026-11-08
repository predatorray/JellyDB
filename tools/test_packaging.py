"""Packaging invariants shared by GNU/Linux and macOS build hosts."""
import hashlib
import importlib.util
import os
from pathlib import Path
import tarfile
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "scripts/create-archive.py"
spec = importlib.util.spec_from_file_location("create_archive", MODULE)
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class PackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "runtime"
        (self.source / "bin").mkdir(parents=True)
        (self.source / "empty").mkdir()
        self.script = self.source / "bin/run.sh"
        self.script.write_bytes(b"#!/bin/sh\nexit 0\n")
        self.script.chmod(0o700)
        self.payload = self.source / "long library name with spaces" / ("x" * 110 + ".jar")
        self.payload.parent.mkdir()
        self.payload.write_bytes(bytes(range(256)))
        self.payload.chmod(0o600)
        self.output = self.root / "jellydb-runtime.tar.gz"

    def tearDown(self):
        self.temp.cleanup()

    def test_content_paths_checksums_and_normalized_metadata(self):
        packaging.create_archive(self.source, self.output)
        with tarfile.open(self.output, "r:gz") as archive:
            self.assertEqual(b"#!/bin/sh\nexit 0\n", archive.extractfile("./bin/run.sh").read())
            member = archive.getmember("./" + self.payload.relative_to(self.source).as_posix())
            self.assertEqual(self.payload.read_bytes(), archive.extractfile(member).read())
            self.assertEqual(0o644, member.mode)
            self.assertEqual(0o755, archive.getmember("./bin/run.sh").mode)
            self.assertTrue(archive.getmember("./empty").isdir())
            for member in archive.getmembers():
                self.assertEqual((0, 0, "", "", packaging.ARCHIVE_EPOCH),
                                 (member.uid, member.gid, member.uname, member.gname, member.mtime))
                self.assertFalse(member.name.startswith("/"))

    def test_reproducible_after_host_mtime_and_non_executable_permission_changes(self):
        packaging.create_archive(self.source, self.output)
        before = self.output.read_bytes()
        for path in self.source.rglob("*"):
            os.utime(path, (123, 456))
        self.script.chmod(0o755)
        self.payload.chmod(0o644)
        other = self.root / "different-output-name.tar.gz"
        packaging.create_archive(self.source, other)
        self.assertEqual(hashlib.sha256(before).digest(), hashlib.sha256(other.read_bytes()).digest())
        self.assertEqual(b"\0\0\0\0", before[4:8])  # gzip timestamp

    def test_unsupported_file_preserves_previous_archive_and_removes_temporary_file(self):
        self.output.write_bytes(b"previous complete archive")
        (self.source / "unexpected-link").symlink_to(self.script)
        with self.assertRaises(ValueError):
            packaging.create_archive(self.source, self.output)
        self.assertEqual(b"previous complete archive", self.output.read_bytes())
        self.assertEqual([self.output], list(self.root.glob("jellydb-runtime.tar.gz*")))

    def test_output_cannot_be_archived_into_itself(self):
        with self.assertRaises(ValueError):
            packaging.create_archive(self.source, self.source / "nested.tar.gz")


if __name__ == "__main__":
    unittest.main()
