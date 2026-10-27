#!/usr/bin/env python3
"""Verify and restore the pinned source archives without extracting them."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(data)
    return value.hexdigest()


def basename(value):
    if not isinstance(value, str) or Path(value).name != value or value in ('', '.', '..'):
        raise ValueError('Archive names must be plain filenames')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    source = root / 'sources'
    output = args.output or root / 'source-archives'
    if not args.verify_only:
        output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((source / 'archives.json').read_text())
    for record in manifest['archives']:
        name = basename(record['name'])
        target = output / name
        if target.exists() and not args.verify_only:
            if target.stat().st_size != record['bytes'] or digest(target) != record['sha256']:
                raise ValueError(f'Refusing to overwrite different contents: {target}')
        temporary = None
        destination = None
        try:
            if not args.verify_only and not target.exists():
                fd, path = tempfile.mkstemp(prefix=name + '.', suffix='.partial', dir=output)
                temporary = Path(path)
                destination = os.fdopen(fd, 'wb')
            complete = hashlib.sha256()
            count = 0
            for part in record['parts']:
                path = source / basename(part['name'])
                partial = hashlib.sha256()
                part_count = 0
                with path.open('rb') as stream:
                    for data in iter(lambda: stream.read(1024 * 1024), b''):
                        partial.update(data)
                        complete.update(data)
                        part_count += len(data)
                        count += len(data)
                        if destination is not None:
                            destination.write(data)
                if part_count != part['bytes'] or partial.hexdigest() != part['sha256']:
                    raise ValueError(f'Part checksum mismatch: {path.name}')
            if count != record['bytes'] or complete.hexdigest() != record['sha256']:
                raise ValueError(f'Archive checksum mismatch: {name}')
            if destination is not None:
                destination.close()
                destination = None
                # Link instead of replace: never overwrite a file created during verification.
                os.link(temporary, target)
            print(f'OK {name}: {count} bytes, {complete.hexdigest()}')
        finally:
            if destination is not None:
                destination.close()
            if temporary is not None:
                temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
