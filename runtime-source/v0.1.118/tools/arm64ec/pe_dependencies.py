#!/usr/bin/env python3
"""Read Windows imports, forwarded exports and API sets without executing a PE.

The resolver models a single Wine system directory. It deliberately does not
claim that statically complete imports prove successful game execution.
"""
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_runtime_candidate import bounded, unpack, pe_info


def module_name(name):
    name = name.lower()
    return name if '.' in name else name + '.dll'


class PeImage:
    def __init__(self, data):
        self.data = data
        self.info = pe_info(data)
        if self.info is None:
            raise ValueError('Not a PE image')
        pe, = unpack('<I', data, 60)
        count, = unpack('<H', data, pe + 6)
        optional = pe + 24
        opt_size, = unpack('<H', data, pe + 20)
        magic, = unpack('<H', data, optional)
        self.bits = 64 if magic == 0x20b else 32
        self.header_size, = unpack('<I', data, optional + 60)
        self.image_base, = unpack('<Q' if self.bits == 64 else '<I', data,
                                  optional + (24 if self.bits == 64 else 28))
        directory_offset = 112 if self.bits == 64 else 96
        directory_count, = unpack('<I', data, optional + directory_offset - 4)
        if directory_count > 16 or directory_offset + directory_count * 8 > opt_size:
            raise ValueError('Invalid PE directories')
        self.directories = [unpack('<II', data, optional + directory_offset + i * 8)
                            for i in range(directory_count)]
        self.sections = []
        for i in range(count):
            base = optional + opt_size + i * 40
            name = bounded(data, base, 8).split(b'\0', 1)[0].decode('ascii')
            virtual_size, rva, size, raw = unpack('<IIII', data, base + 8)
            bounded(data, raw, size)
            self.sections.append((name, rva, size, raw))

    def offset(self, rva, size=1):
        if 0 <= rva and rva + size <= self.header_size:
            bounded(self.data, rva, size)
            return rva
        for _, start, length, raw in self.sections:
            if start <= rva and rva + size <= start + length:
                result = raw + rva - start
                bounded(self.data, result, size)
                return result
        raise ValueError('PE RVA outside file-backed sections: ' + hex(rva))

    def ec_view(self):
        """Select ARM64X's x64/EC metadata without changing packaged bytes.

        ARM64X redirects its export/import directories with dynamic fixups;
        reading the native ARM64 export table reports false missing exports.
        Format: Wine 11.0 ntdll/unix/virtual.c update_arm64x_mapping.
        """
        rva, size = self.directory(10)
        if self.bits != 64 or not rva or size < 230:
            return self
        config = self.offset(rva, 230)
        declared, = unpack('<I', self.data, config)
        if declared < 230:
            return self
        offset, section = unpack('<IH', self.data, config + 224)
        if not section:
            return self
        if section > len(self.sections):
            raise ValueError('Invalid ARM64X relocation section')
        table = self.sections[section - 1][1] + offset
        version, length = unpack('<II', self.data, self.offset(table, 8))
        pos, end = table + 8, table + 8 + length
        self.offset(table + 8, length)
        if version != 1:
            raise ValueError('Unsupported dynamic relocation version')
        patched = bytearray(self.data)
        changed = False
        while pos < end:
            if end - pos < 12:
                raise ValueError('Truncated dynamic relocation')
            symbol, count = unpack('<QI', self.data, self.offset(pos, 12))
            pos += 12
            if pos + count > end:
                raise ValueError('Truncated ARM64X fixups')
            stop = pos + count
            if symbol != 6:
                pos = stop
                continue
            while pos < stop:
                page, block_size = unpack('<II', self.data, self.offset(pos, 8))
                if block_size < 8 or block_size % 2 or pos + block_size > stop:
                    raise ValueError('Invalid ARM64X fixup block')
                block_end = pos + block_size
                pos += 8
                while pos < block_end:
                    record, = unpack('<H', self.data, self.offset(pos, 2))
                    pos += 2
                    if not record:
                        break
                    target, kind, arg = page + (record & 0xfff), (record >> 12) & 3, record >> 14
                    width = 4 if kind == 2 else 1 << arg
                    address = self.offset(target, width)
                    if kind == 0:
                        patched[address:address + width] = bytes(width)
                    elif kind == 1:
                        if width < 2 or pos + width > block_end:
                            raise ValueError('Truncated ARM64X value')
                        patched[address:address + width] = bounded(self.data, self.offset(pos, width), width)
                        pos += width
                    elif kind == 2:
                        if pos + 2 > block_end:
                            raise ValueError('Truncated ARM64X delta')
                        delta, = unpack('<H', self.data, self.offset(pos, 2))
                        pos += 2
                        delta *= 8 if arg & 2 else 4
                        old, = unpack('<I', patched, address)
                        struct.pack_into('<I', patched, address, (old + (-delta if arg & 1 else delta)) & 0xffffffff)
                    else:
                        raise ValueError('Unknown ARM64X fixup kind')
                    changed = True
                pos = block_end
            pos = stop
        return PeImage(bytes(patched)) if changed else self

    def string(self, rva):
        start = self.offset(rva)
        end = self.data.index(b'\0', start, min(len(self.data), start + 4096))
        self.offset(rva, end - start + 1)
        return self.data[start:end].decode('ascii')

    def directory(self, index):
        return self.directories[index] if index < len(self.directories) else (0, 0)

    def exports(self):
        rva, size = self.directory(0)
        if not rva:
            return {}
        fields = unpack('<IIHHIIIIIII', self.data, self.offset(rva, 40))
        ordinal_base, count, names, eat, ent, eot = fields[5:]
        if count > 100000 or names > 100000:
            raise ValueError('Excess export entries')
        addresses = unpack('<' + 'I' * count, self.data, self.offset(eat, count * 4))
        result = {}
        for i, address in enumerate(addresses):
            if address:
                result['#' + str(ordinal_base + i)] = self.string(address) if rva <= address < rva + size else None
        for i in range(names):
            name_rva, = unpack('<I', self.data, self.offset(ent + i * 4, 4))
            ordinal, = unpack('<H', self.data, self.offset(eot + i * 2, 2))
            if ordinal >= count:
                raise ValueError('Invalid export ordinal')
            key = '#' + str(ordinal_base + ordinal)
            if key in result:
                result[self.string(name_rva)] = result[key]
        return result

    def thunks(self, table, value_base=0):
        stride = self.bits // 8
        flag = 1 << (self.bits - 1)
        result = []
        for i in range(100000):
            value, = unpack('<Q' if self.bits == 64 else '<I', self.data,
                            self.offset(table + i * stride, stride))
            if not value:
                return result
            result.append('#' + str(value & 0xffff) if value & flag else self.string(value - value_base + 2))
        raise ValueError('Unterminated PE import table')

    def imports(self):
        result = []
        for delayed, index, stride in [(False, 1, 20), (True, 13, 32)]:
            rva, size = self.directory(index)
            if not rva:
                continue
            terminated = False
            for i in range(min(size // stride, 10000)):
                fields = unpack('<' + 'I' * (stride // 4), self.data, self.offset(rva + i * stride, stride))
                if not any(fields):
                    terminated = True
                    break
                if delayed:
                    subtract = 0 if fields[0] & 1 else self.image_base
                    name = fields[1] - subtract
                    table = (fields[4] or fields[3]) - subtract
                else:
                    name, table = fields[3], fields[0] or fields[4]
                    subtract = 0
                result.append({'module': module_name(self.string(name)), 'symbols': self.thunks(table, subtract), 'delayed': delayed})
            if not terminated:
                raise ValueError('Unterminated PE import descriptors')
        return result

    def api_sets(self):
        section = next((s for s in self.sections if s[0] == '.apiset'), None)
        if section is None:
            return {}
        blob = bounded(self.data, section[3], section[2])
        version, size, flags, count, entries, _, _ = unpack('<7I', blob, 0)
        if version != 6 or size > len(blob) or count > 100000:
            raise ValueError('Unsupported API-set namespace')
        blob = bounded(blob, 0, size)
        def wide(offset, length):
            return bounded(blob, offset, length).decode('utf-16-le')
        result = {}
        for i in range(count):
            _, name, length, _, values, value_count = unpack('<6I', blob, entries + i * 24)
            if value_count > 10000:
                raise ValueError('Excess API-set aliases')
            hosts = []
            for j in range(value_count):
                _, alias, alias_length, value, value_length = unpack('<5I', blob, values + j * 20)
                hosts.append((module_name(wide(alias, alias_length)) if alias_length else '',
                              module_name(wide(value, value_length)) if value_length else ''))
            result[module_name(wide(name, length))] = hosts
        return result


def resolve_module(name, importer, modules, api_sets):
    name = module_name(name)
    if name in modules:
        return name
    hosts = api_sets.get(name, [])
    choices = [host for alias, host in hosts if alias == importer] or [host for alias, host in hosts if not alias]
    return next((host for host in choices if host in modules), None)


def check_dependencies(images):
    """Return unresolved import/forwarder symbols and missing DLLs, by importer."""
    images = {module_name(name): image for name, image in images.items()}
    modules = {module_name(name): image.exports() for name, image in images.items()}
    api_sets = images['apisetschema.dll'].api_sets() if 'apisetschema.dll' in images else {}
    problems = []
    def resolve(name, symbol, importer, chain=()):
        target = resolve_module(name, importer, modules, api_sets)
        if target is None:
            return 'missing module: ' + module_name(name)
        key = (target, symbol)
        if key in chain:
            return 'forwarder cycle: ' + repr(chain + (key,))
        if len(chain) >= 64:
            return 'excess forwarder depth'
        if symbol not in modules[target]:
            return 'missing export: ' + target + '!' + symbol
        forward = modules[target][symbol]
        if forward:
            name, separator, symbol = forward.rpartition('.')
            if not separator or not name or not symbol:
                return 'invalid forwarder: ' + forward
            return resolve(name, symbol, target, chain + (key,))
        return None
    for importer, image in sorted(images.items()):
        for entry in image.imports():
            missing = {}
            for symbol in entry['symbols']:
                error = resolve(entry['module'], symbol, importer)
                if error:
                    missing[symbol] = error
            if missing:
                problems.append({'importer': importer, 'kind': 'delayImport' if entry['delayed'] else 'import',
                                 'module': entry['module'], 'symbols': missing})
        named_forwarders = {value for key, value in modules[importer].items() if not key.startswith('#')}
        for symbol, forward in modules[importer].items():
            if forward and (not symbol.startswith('#') or forward not in named_forwarders):
                error = resolve(importer, symbol, importer)
                if error:
                    problems.append({'importer': importer, 'kind': 'forward', 'symbol': symbol,
                                     'target': forward, 'error': error})
    return {'modules': len(images), 'apiSets': len(api_sets), 'problems': problems}
