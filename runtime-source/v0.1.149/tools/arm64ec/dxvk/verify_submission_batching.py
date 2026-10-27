#!/usr/bin/env python3
"""Compare actual DXVK tracker code with the pinned original, then test boundaries."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
COMMIT = 'c3dd74be6baec53786d4e064a572185b70347a17'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip() == COMMIT
    with tempfile.TemporaryDirectory(prefix='aura-dxvk-tracker-') as tmp:
        root = Path(tmp)
        for directory, patched in [('baseline', False), ('patched', True)]:
            folder = root / directory
            folder.mkdir()
            for name in ['util_flush.h', 'util_flush.cpp']:
                data = (source / 'src/util' / name).read_bytes() if patched else subprocess.check_output(
                    ['git', 'show', f'{COMMIT}:src/util/{name}'], cwd=source)
                if not patched:
                    data = data.replace(b'namespace dxvk {', b'namespace baseline {')
                (folder / name).write_bytes(data)
        exe = root / 'test'
        subprocess.run(['g++', '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                        '-include', 'algorithm', '-I', str(root),
                        str(HERE / 'test_submission_batching.cpp'),
                        str(root / 'baseline/util_flush.cpp'), str(root / 'patched/util_flush.cpp'),
                        '-o', str(exe)], check=True)
        result = json.loads(subprocess.check_output([str(exe)], text=True))
    result.update(source_commit=COMMIT,
                  patch_sha256=hashlib.sha256((HERE / 'submission-batching.patch').read_bytes()).hexdigest())
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
