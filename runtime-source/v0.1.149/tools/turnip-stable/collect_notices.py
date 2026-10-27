#!/usr/bin/env python3
"""Retain a notice superset from the pinned stable Mesa tree, without executing it."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
LOCK = json.loads((HERE / 'sources.json').read_text())['mesa']
COMMENTS = re.compile(r'/\*.*?\*/|(?m:^(?:[ \t]*//[^\n]*\n)+)|(?m:^(?:[ \t]*\#[^\n]*\n)+)', re.S)
NOTICE = re.compile(r'copyright|SPDX-License-Identifier', re.I)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    assert hashlib.sha256(args.archive.read_bytes()).hexdigest() == LOCK['sha256']
    target = ROOT / 'app/src/main/assets/licenses/turnip-26-2-5'
    target.mkdir(parents=True, exist_ok=True)
    blocks = {}
    with tarfile.open(args.archive) as source:
        for entry in source:
            if not entry.isfile() or entry.size > 4 * 1024 * 1024:
                continue
            name = entry.name.split('/', 1)[-1]
            if name == 'docs/license.rst' or name.startswith('licenses/'):
                if name == 'docs/license.rst' or '/' not in name.removeprefix('licenses/'):
                    (target / Path(name).name).write_bytes(source.extractfile(entry).read())
                continue
            if not name.startswith(('src/', 'include/')) or not name.endswith(('.c', '.h', '.cpp', '.hpp', '.cc', '.cxx', '.rs', '.py', '.mako')):
                continue
            text = source.extractfile(entry).read().decode('utf-8', errors='replace')
            for match in COMMENTS.finditer(text):
                if NOTICE.search(match.group()):
                    blocks.setdefault(match.group(), []).append(name)
    text = ('Mesa 26.2.5 source copyright and licence comments\nSource: ' + LOCK['url'] + '\n'
            'Upstream source notice superset; includes unlinked components and is not a binary link map.\n'
            'Identical comments are retained once, with all source paths. Individual licences are unchanged.\n')
    text += ''.join('\n===== ' + ', '.join(names) + ' =====\n' + block + '\n' for block, names in blocks.items())
    (target / 'mesa-source-notices.txt').write_text(text)
    records = {str(p.relative_to(ROOT)): {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
               for p in sorted(target.iterdir()) if p.is_file()}
    (HERE / 'notices.json').write_text(json.dumps({'source': LOCK, 'files': records,
        'uniqueCommentBlocks': len(blocks), 'scope': 'Source notice superset, not a linked component list'}, indent=2) + '\n')
    print(f'Retained {len(records)} licence files and {len(blocks)} unique source notice blocks')


if __name__ == '__main__':
    main()
