#!/usr/bin/env python3
"""Cross-compile the real Mesa context layout and reject the former bad layout.

Uses the module's compile database, NDK and generated headers. No Android
execution, driver import or game performance is claimed by this check.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile

FIXED = ('struct zink_context {\n   struct pipe_context base;\n'
         '   void *aura_present;\n   bool aura_present_disabled;')
LEGACY = ('struct zink_context {\n   void *aura_present;\n'
          '   bool aura_present_disabled;\n   struct pipe_context base;')
ASSERTION = '_Static_assert(offsetof(struct zink_context, base) == 0, "Gallium context base must stay first");'
FIELDS = ('pointer_bytes', 'pipe_context_bytes', 'base_offset', 'transfer_pool_offset',
          'unsync_pool_offset', 'pool_bytes', 'pool_parent_offset', 'pool_free_offset',
          'parent_item_size_offset')
HARNESS = '''#include "zink_types.h"
#include <stddef.h>
#include <stdint.h>
const uint64_t aura_layout[] __attribute__((used, section(".aura_layout"))) = {
   sizeof(void *), sizeof(struct pipe_context), offsetof(struct zink_context, base),
   offsetof(struct zink_context, transfer_pool), offsetof(struct zink_context, transfer_pool_unsync),
   sizeof(struct slab_child_pool), offsetof(struct slab_child_pool, parent),
   offsetof(struct slab_child_pool, free), offsetof(struct slab_parent_pool, item_size),
};
bool aura_context_identity(struct zink_context *ctx);
bool aura_context_identity(struct zink_context *ctx) { return zink_context(&ctx->base) == ctx; }
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--original-module', type=Path,
                        help='Optional 0.1.135 module for matching the observed fault to slab_zalloc')
    args = parser.parse_args()
    records = json.loads((args.build / 'compile_commands.json').read_text())
    record = next(r for r in records if r['file'].endswith('/zink_context.c'))
    cwd = Path(record['directory'])
    source = (cwd / record['file']).resolve()
    types = source.with_name('zink_types.h').read_text()
    assert types.count(FIXED) == 1 and LEGACY not in types
    assert ASSERTION in source.read_text()
    assert re.search(r'zink_context\(struct pipe_context \*context\)\s*\{\s*return \(struct zink_context \*\)context;\s*\}', types)
    original = shlex.split(record['command'])
    flags, index = [], 1
    while index < len(original):
        arg = original[index]
        if arg in ('-o', '-MF', '-MQ', '-MT'):
            index += 2
            continue
        if arg not in ('-c', '-MD', '-MMD', record['file']):
            flags.append(arg)
        index += 1
    clang = Path(original[0])
    objcopy, objdump = [clang.with_name('llvm-' + n) for n in ('objcopy', 'objdump')]
    results = {}
    slab_match = None
    with tempfile.TemporaryDirectory(prefix='aura-context-layout-') as folder:
        folder = Path(folder)
        for variant, header in [('fixed', types), ('legacy', types.replace(FIXED, LEGACY))]:
            root = folder / variant
            root.mkdir()
            (root / 'zink_types.h').write_text(header)
            c, obj, binary = [root / n for n in ('layout.c', 'layout.o', 'layout.bin')]
            c.write_text(HARNESS)
            command = [str(clang), *flags, '-c', str(c), '-o', str(obj)]
            subprocess.run(command, cwd=cwd, check=True, capture_output=True)
            subprocess.run([objcopy, '--dump-section', f'.aura_layout={binary}', obj], check=True)
            values = struct.unpack('<' + 'Q' * len(FIELDS), binary.read_bytes())
            layout = dict(zip(FIELDS, values))
            disassembly = subprocess.check_output([objdump, '-d', '--no-show-raw-insn', obj], text=True)
            identity = disassembly.split('<aura_context_identity>:', 1)[1]
            expected = r'mov\s+w0, #0x1' if variant == 'fixed' else r'mov\s+w0, wzr'
            assert re.search(expected, identity) and re.search(r'\bret\b', identity), identity
            c.write_text(HARNESS + ASSERTION + '\n')
            guarded = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
            if variant == 'fixed':
                assert guarded.returncode == 0, guarded.stderr
            else:
                assert guarded.returncode != 0 and 'Gallium context base must stay first' in guarded.stderr
            results[variant] = dict(layout=layout, callback_identity=variant == 'fixed',
                                    production_guard_accepts=guarded.returncode == 0)
        if args.original_module:
            original = args.original_module.read_bytes()
            assert hashlib.sha256(original).hexdigest() == 'd9e2110cb96fbdf12a1c06bc3bea5705a31b992bc6e4abd8989a90b62f4c4d56'
            slab = args.build.resolve() / 'src/util/libmesa_util.a.p/slab.c.o'
            section = folder / 'slab-zalloc.bin'
            subprocess.run([objcopy, '--dump-section', f'.text.slab_zalloc={section}', slab], check=True)
            reference = bytearray(section.read_bytes())
            assert len(reference) == 60
            observed = bytearray(original[0x6e6960:0x6e6960 + len(reference)])
            for offset, opcode in ((16, 0x94000000), (44, 0x14000000)):
                assert struct.unpack_from('<I', reference, offset)[0] == opcode
                assert struct.unpack_from('<I', observed, offset)[0] & 0xfc000000 == opcode
                observed[offset:offset + 4] = reference[offset:offset + 4]
            assert observed == reference
            slab_match = dict(function_bytes=60, non_relocated_bytes_verified=52,
                              branch_relocations=2, fault_instruction_offset=28,
                              normalized_sha256=hashlib.sha256(reference).hexdigest())
    fixed, legacy = [results[v]['layout'] for v in ('fixed', 'legacy')]
    assert fixed['pointer_bytes'] == legacy['pointer_bytes'] == 8
    assert fixed['base_offset'] == 0 and legacy['base_offset'] == 16
    assert fixed['transfer_pool_offset'] == legacy['transfer_pool_offset'] == 0x500
    assert legacy['pool_parent_offset'] == 0 and legacy['parent_item_size_offset'] == 12
    assert legacy['base_offset'] == legacy['pool_free_offset']
    result = dict(schema='SHARED_GPU_CONTEXT_LAYOUT_V1', target='aarch64-linux-android34',
                  header_sha256=hashlib.sha256(types.encode()).hexdigest(), variants=results,
                  legacy_wrong_parent_alias='transfer_pool.free',
                  original_slab_zalloc_match=slab_match,
                  android_execution_tested=False, ahb_import_tested=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
