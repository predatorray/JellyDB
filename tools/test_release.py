"""Release version validation and unprivileged installation/upgrade safety."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / ('scripts/' + name + '.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


release = load('release')
installer = load('install-http')


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.stage = self.root / 'stage'

    def tearDown(self): self.temp.cleanup()

    def fixture(self, tag='v0.1.0-rc.1', payload='jar'):
        root = self.root / ('source-' + tag + '-' + str(len(list(self.root.iterdir()))))
        (root / 'bin').mkdir(parents=True); (root / 'conf').mkdir()
        (root / 'bin/run.sh').write_text('#!/bin/sh\nexit 0\n'); (root / 'bin/run.sh').chmod(0o755)
        (root / 'app.jar').write_text(payload)
        (root / 'conf/http.env.example').write_text('JELLY_ZK_CONNECT=private:2181\n')
        (root / 'conf/jellydb.service').write_text('[Service]\nUser=jellydb\n')
        (root / 'release.json').write_text(json.dumps({'tag':tag, 'sourceCommit':'a'*40, 'platform':'linux-amd64'}))
        release.checksums(root)
        return root

    def install(self, source):
        with contextlib.redirect_stdout(io.StringIO()): return installer.install(source,self.stage)

    def test_tag_validation_rejects_injection_and_version_mismatch(self):
        self.assertTrue(release.validate_tag('v0.1.0-rc.1','0.1.0-SNAPSHOT'))
        self.assertTrue(release.validate_tag('v0.1.0','0.1.0-SNAPSHOT'))
        self.assertFalse(release.validate_tag('v1.2.3','1.2.3'))
        for tag in ['main','v0.1.0/../../etc','v0.1.0;echo bad','v01.1.0','v0.2.0','v0.1.0\n']:
            with self.assertRaises(ValueError): release.validate_tag(tag,'0.1.0-SNAPSHOT')

    def test_staging_install_repeat_and_upgrade_preserve_configuration(self):
        source=self.fixture(); destination=self.install(source)
        self.assertEqual(destination,(self.stage/'opt/jellydb/current').resolve())
        self.assertTrue((self.stage/'opt/jellydb/bin/run.sh').is_symlink())
        env=self.stage/'etc/jellydb/http.env'; env.write_text('operator settings\n')
        unit=self.stage/'etc/systemd/system/jellydb.service';unit.write_text('custom unit\n')
        self.assertEqual(destination,self.install(source))
        upgraded=self.install(self.fixture('v0.1.0-rc.2','new jar'))
        self.assertNotEqual(destination,upgraded);self.assertTrue(destination.exists())
        self.assertEqual(upgraded,(self.stage/'opt/jellydb/current').resolve())
        self.assertEqual('operator settings\n',env.read_text());self.assertEqual(0o600,env.stat().st_mode&0o777)
        self.assertEqual('custom unit\n',unit.read_text())

    def test_tamper_or_extra_files_are_rejected_before_install(self):
        source=self.fixture();(source/'app.jar').write_text('changed')
        with self.assertRaises(ValueError):self.install(source)
        self.assertFalse(self.stage.exists())
        source=self.fixture();(source/'unexpected').write_text('extra')
        with self.assertRaises(ValueError):self.install(source)
        self.assertFalse(self.stage.exists())

    def test_same_version_different_bytes_do_not_replace_active_release(self):
        first=self.install(self.fixture())
        with self.assertRaises(ValueError):self.install(self.fixture(payload='different'))
        self.assertEqual(first,(self.stage/'opt/jellydb/current').resolve())
        self.assertEqual('jar',(first/'app.jar').read_text())

    def test_symlinks_and_non_symlink_current_are_refused(self):
        source=self.fixture();(source/'outside').symlink_to('/etc/passwd')
        with self.assertRaises(ValueError):self.install(source)
        source=self.fixture(); current=self.stage/'opt/jellydb/current';current.mkdir(parents=True)
        with self.assertRaises(ValueError):self.install(source)
        self.assertTrue(current.is_dir());self.assertFalse(current.is_symlink())


if __name__ == '__main__':unittest.main()
