#!/usr/bin/env python3
"""Reject engine/source edits hidden behind the immutable dependency build version."""
import json, pathlib, subprocess, sys
import xml.etree.ElementTree as ET
root=pathlib.Path(sys.argv[1])
project=pathlib.Path(__file__).resolve().parents[1]
pin=json.loads((project/'candybox.lock').read_text())
revision=pin['commit']
assert ET.parse(project/'pom.xml').findtext('{*}properties/{*}candybox.version')==pin['version'], 'POM and candybox.lock disagree'
assert subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()==revision
changed=subprocess.check_output(['git','-C',str(root),'diff','--name-only'],text=True).splitlines()
for name in changed:
    assert pathlib.Path(name).name=='pom.xml', f'Unexpected upstream change: {name}'
    original=subprocess.check_output(['git','-C',str(root),'show',f'{revision}:{name}'])
    expected=original.replace(pin['upstream_version'].encode(),pin['version'].encode())
    assert (root/name).read_bytes() in (original,expected), f'Unexpected POM edit: {name}'
