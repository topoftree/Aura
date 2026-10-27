#!/usr/bin/env python3
"""Read a runtime archive without installing/executing it. ABI evidence, not a launch test.

In particular, an ARM64EC PE can have Machine=AMD64. A filename or that field
alone is not enough to distinguish it from an ordinary x64 DLL.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import struct
import tarfile

import zstandard


def bounded(data, offset, size):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ValueError("Truncated executable structure")
    return data[offset:offset + size]


def unpack(fmt, data, offset):
    return struct.unpack(fmt, bounded(data, offset, struct.calcsize(fmt)))


def elf_info(data):
    if data[:4] != b"\x7fELF":
        return None
    if data[4:6] != b"\x02\x01":
        raise ValueError("Only little-endian ELF64 is supported")
    machine, = unpack("<H", data, 18)
    phoff, = unpack("<Q", data, 32)
    stride, count = unpack("<HH", data, 54)
    if stride < 56 or count > 256:
        raise ValueError("Invalid ELF program table")
    segments = [unpack("<IIQQQQQQ", data, phoff + i * stride) for i in range(count)]
    interp = next((bounded(data, p[2], p[5]).rstrip(b"\0").decode() for p in segments if p[0] == 3), None)
    dynamic = next((p for p in segments if p[0] == 2), None)
    tags = []
    if dynamic:
        for pos in range(dynamic[2], dynamic[2] + dynamic[5], 16):
            tag, value = unpack("<QQ", data, pos)
            if tag == 0:
                break
            tags.append((tag, value))

    def strings(tag_number):
        offsets = [v for t, v in tags if t == tag_number]
        if not offsets:
            return []
        table = next((v for t, v in tags if t == 5), None)
        length = next((v for t, v in tags if t == 10), None)
        if table is None or length is None:
            raise ValueError("ELF dynamic string table is missing")
        load = next((p for p in segments if p[0] == 1 and p[3] <= table < p[3] + p[5]), None)
        if load is None or table + length > load[3] + load[5]:
            raise ValueError("ELF dynamic strings are outside file-backed data")
        raw = bounded(data, load[2] + table - load[3], length)
        result = []
        for offset in offsets:
            if not 0 <= offset < len(raw) or b"\0" not in raw[offset:]:
                raise ValueError("Invalid ELF dynamic string")
            result.append(raw[offset:raw.index(b"\0", offset)].decode())
        return result

    needed = strings(1)
    if interp == "/system/bin/linker64" or "libc.so" in needed:
        abi = "bionic"
    elif (interp and "ld-linux" in interp) or "libc.so.6" in needed:
        abi = "glibc"
    else:
        abi = "undetermined"
    return dict(format="ELF64", machine=hex(machine), abi=abi, interpreter=interp,
                needed=needed, soname=strings(14), searchPaths=strings(15) + strings(29),
                minimumLoadAlignment=min((p[7] for p in segments if p[0] == 1), default=0))


def pe_info(data):
    if data[:2] != b"MZ":
        return None
    pe, = unpack("<I", data, 60)
    if pe < 64 or bounded(data, pe, 4) != b"PE\0\0":
        return None  # A DOS stub/resource can start with MZ without being a PE.
    machine, count = unpack("<HH", data, pe + 4)
    opt_size, flags = unpack("<HH", data, pe + 20)
    optional = pe + 24
    magic, = unpack("<H", data, optional)
    if count > 96 or magic not in (0x10b, 0x20b):
        raise ValueError("Unsupported PE headers")
    bounded(data, optional, opt_size)
    sections = []
    for i in range(count):
        base = optional + opt_size + i * 40
        bounded(data, base, 40)
        virtual_size, rva, raw_size, raw = unpack("<IIII", data, base + 8)
        sections.append((rva, raw_size, raw))

    def rva_offset(rva, size):
        for start, length, raw in sections:
            if start <= rva and rva + size <= start + length:
                bounded(data, raw + rva - start, size)
                return raw + rva - start
        raise ValueError("PE directory is outside file-backed sections")

    hybrid = False
    # IMAGE_LOAD_CONFIG_DIRECTORY64.CHPEMetadataPointer is at byte 200.
    if magic == 0x20b:
        if opt_size < 112:
            raise ValueError("Truncated PE32+ optional header")
        image_base, = unpack("<Q", data, optional + 24)
        directory_count, = unpack("<I", data, optional + 108)
        if directory_count > 10:
            if opt_size < 112 + 11 * 8:
                raise ValueError("Truncated PE load-config directory")
            config_rva, config_size = unpack("<II", data, optional + 112 + 10 * 8)
            if config_rva and config_size:
                config = rva_offset(config_rva, min(config_size, 208))
                declared_size, = unpack("<I", data, config)
                if min(config_size, declared_size) >= 208:
                    metadata, = unpack("<Q", data, config + 200)
                    if metadata:
                        if metadata < image_base:
                            raise ValueError("Invalid CHPE metadata pointer")
                        rva_offset(metadata - image_base, 4)
                        hybrid = True
    arch = {0x14c: "x86", 0x8664: "x64", 0xaa64: "arm64", 0xa641: "arm64ec", 0xa64e: "arm64x"}.get(machine, "unknown")
    return dict(format="PE", machine=hex(machine), architecture=arch, chpeMetadata=hybrid, dll=bool(flags & 0x2000))


def inspect(path, max_expanded=2 * 1024**3, max_file=128 * 1024**2):
    binaries, links, names = {}, {}, set()
    profile = None
    expanded = 0
    with path.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as stream:
        with tarfile.open(fileobj=stream, mode="r|") as archive:
            for entry in archive:
                name = entry.name.removeprefix("./").rstrip("/")
                if not name or name == ".":
                    continue
                if name.startswith("/") or ".." in PurePosixPath(name).parts or "\\" in name or name in names:
                    raise ValueError("Unsafe or duplicate archive path: " + name)
                names.add(name)
                if len(names) > 20000:
                    raise ValueError("Too many archive entries")
                if entry.isdir():
                    continue
                if entry.issym() or entry.islnk():
                    links[name] = entry.linkname
                    continue
                if not entry.isfile() or not 0 <= entry.size <= max_file:
                    raise ValueError("Unsupported archive member: " + name)
                expanded += entry.size
                if expanded > max_expanded:
                    raise ValueError("Archive exceeds expanded size limit")
                data = archive.extractfile(entry).read(entry.size + 1)
                if len(data) != entry.size:
                    raise ValueError("Truncated archive member: " + name)
                if name == "profile.json":
                    if len(data) > 65536:
                        raise ValueError("Component profile too large")
                    profile = json.loads(data)
                info = elf_info(data) or pe_info(data)
                if info:
                    binaries[name] = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), **info)
        # Consume the frame checksum. No archive links are followed or installed.
        trailing = 0
        while chunk := stream.read(1024 * 1024):
            trailing += len(chunk)
            if trailing > 1024 * 1024:
                raise ValueError("Excess archive trailing data")
    with path.open("rb") as raw:
        digest = hashlib.file_digest(raw, "sha256").hexdigest()
    return dict(sha256=digest, bytes=path.stat().st_size, expandedBytes=expanded,
                profile=profile, binaries=binaries, links=links,
                limits=["Static file inspection only; no executable was run or installed.",
                        "DT_NEEDED does not include every dynamically loaded graphics, audio or codec dependency.",
                        "A matching ABI does not prove Android loading, IPC, controller, GPU or game compatibility."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = inspect(args.archive)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(f"Inspected {len(report['binaries'])} executables; report: {args.output}")


if __name__ == "__main__":
    main()
