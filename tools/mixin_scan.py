"""Shared source scanning for Mixin overlap reports."""

import os
from pathlib import Path
import re

MIXIN_RE = re.compile(r'@Mixin\s*\(([^)]*)\)', re.S)


def mixin_sources(root):
    for directory, _, files in os.walk(root):
        if 'mixin' not in directory.replace(os.sep, '/').split('/'):
            continue
        for name in files:
            if name.endswith('.java'):
                path = Path(directory) / name
                source = path.read_text(encoding='utf-8', errors='ignore')
                match = MIXIN_RE.search(source)
                if match:
                    yield name, match.group(1), source


def target_classes(body):
    return re.findall(r'(?:value\s*=\s*)?\{?\s*([A-Za-z0-9_.$]+)\.class', body)


def named_targets(body):
    return re.findall(r'targets\s*=\s*\{?\s*"([^"]+)"', body)


def priority(body):
    match = re.search(r'priority\s*=\s*(\d+)', body)
    return match.group(1) if match else '1000'


def simple_names(targets):
    return {name.split('.')[-1].split('$')[-1] for name in targets}
