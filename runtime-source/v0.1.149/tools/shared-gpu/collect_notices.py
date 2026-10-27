#!/usr/bin/env python3
"""Retain copyright/licence comments from the pinned Mesa source archive.

The archive is read without extracting or executing source files. The result is
a superset of notices, not an assertion that every Mesa component is linked.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile

ROOT = Path(__file__).resolve().parents[2]
LOCK = json.loads((Path(__file__).parent / 'sources.lock.json').read_text())['mesa']
COMMIT = LOCK['commit']
ARCHIVE_SHA = LOCK['sha256']
URL = LOCK['url']
COMMENTS = re.compile(r'/\*.*?\*/|(?m:^(?:[ \t]*//[^\n]*\n)+)|(?m:^(?:[ \t]*\#[^\n]*\n)+)', re.S)
NOTICE = re.compile(r'copyright|SPDX-License-Identifier', re.I)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    assert hashlib.sha256(args.archive.read_bytes()).hexdigest() == ARCHIVE_SHA
    parts = [f'Mesa source copyright and licence comments\nSource: {URL}\n'
             'This covers the upstream tree, including components not linked into the optional Zink module.\n'
             'It does not change the individual licences or identify the binary link set.\n']
    files = blocks = 0
    with tarfile.open(args.archive, 'r:gz') as source:
        for entry in source:
            if not entry.isfile() or entry.size > 4 * 1024 * 1024:
                continue
            name = entry.name.split('/', 1)[-1]
            if not name.startswith('src/') or not name.endswith(('.c', '.h', '.cpp', '.hpp', '.cc', '.cxx', '.rs', '.py', '.mako')):
                continue
            text = source.extractfile(entry).read().decode('utf-8', errors='replace')
            notices = [match.group() for match in COMMENTS.finditer(text) if NOTICE.search(match.group())]
            if notices:
                files += 1
                blocks += len(notices)
                parts.append(f'\n===== {name} =====\n' + '\n\n'.join(notices))
    output = ROOT / 'app/src/main/assets/licenses/shared-gpu/mesa-source-notices.txt'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(parts) + '\n')
    record = {'url': URL, 'archiveSha256': ARCHIVE_SHA, 'sourceFiles': files, 'noticeBlocks': blocks,
              'output': output.relative_to(ROOT).as_posix(), 'bytes': output.stat().st_size,
              'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
              'scope': 'Upstream source notice superset; not a binary link map'}
    (Path(__file__).parent / 'notice-sources.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
