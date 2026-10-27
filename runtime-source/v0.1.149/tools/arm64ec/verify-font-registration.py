#!/usr/bin/env python3
"""Exercise compiled registry repair against the real APK's prefix (host JVM, not Wine)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import zipfile

import zstandard


def values(text):
    result, section = {}, ""
    for line in text.splitlines():
        if line.startswith("["):
            section = line[1:line.index("]")].lower()
        elif (match := re.match(r'^"([^"]+)"=(.*)$', line)):
            result[section, match[1].lower()] = match[2]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--engine-classes", required=True, type=Path)
    parser.add_argument("--kotlin-stdlib", required=True, type=Path)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    originals = {}
    with zipfile.ZipFile(args.apk) as apk, apk.open("assets/arm64ec/prefix.tzst") as packed, \
            zstandard.ZstdDecompressor().stream_reader(packed) as stream, \
            tarfile.open(fileobj=stream, mode="r|") as archive:
        for member in archive:
            if member.name.removeprefix("./") in (".wine/system.reg", ".wine/user.reg"):
                assert member.isfile() and member.size < 16 * 1024 * 1024
                originals[Path(member.name).name] = archive.extractfile(member).read()
    assert set(originals) == {"system.reg", "user.reg"}
    with tempfile.TemporaryDirectory(prefix="aura-font-registry-") as directory:
        root = Path(directory)
        for name, data in originals.items():
            (root / name).write_bytes(data)
        driver = root / "VerifyFonts.java"
        driver.write_text('''
import java.io.File;
import com.winlator.core.WineBuiltinFontRegistry;
class VerifyFonts {
    public static void main(String[] args) {
        var prefix = new File(args[0]);
        var first = WineBuiltinFontRegistry.INSTANCE.prepare(prefix);
        var again = WineBuiltinFontRegistry.INSTANCE.prepare(prefix);
        if (again.getRegistered() != 0 || again.getRetired() != 0)
            throw new AssertionError("Repeated launch modified registrations");
        System.out.println(first.getRegistered() + "," + first.getRetired());
    }
}
''')
        command = [args.java, "--class-path", os.pathsep.join(str(p.resolve()) for p in
                   (args.engine_classes, args.kotlin_stdlib)), str(driver), str(root)]
        registered, retired = map(int, subprocess.check_output(command, text=True).strip().split(","))
        # The shipped template already has all eight HKLM entries; only the four
        # stale HKCU owners should change. A separate JVM regression covers missing HKLM.
        assert (registered, retired) == (0, 4), (registered, retired)
        assert (root / "system.reg").read_bytes() == originals["system.reg"]
        before, after = (values(data) for data in (originals["user.reg"].decode(), (root / "user.reg").read_text()))
        expected = {(r"software\\wine\\fonts\\external fonts", name) for name in
                    ("tahoma (truetype)", "tahoma bold (truetype)", "marlett (truetype)", "symbol (truetype)")}
        assert before.keys() - after.keys() == expected
        assert after.keys() - before.keys() == set()
        assert all(before[key] == value for key, value in after.items())
        report = dict(passed=True, hostJvmOnly=True, apkName=args.apk.name,
                      registryInputs={name: dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                                      for name, data in originals.items()},
                      registered=registered, retiredExternal=retired, systemRegistryBytePreserved=True,
                      unrelatedUserValuesPreserved=True, repeatedLaunchNoChange=True,
                      androidWineExecuted=False, steamLoginTested=False)
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
