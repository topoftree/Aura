"""Fixed-width package relocation for bundled ELF and tar links; never shifts binary offsets."""

LEGACY_APP_DIRS = (b"/data/data/com.winlator", b"/data/data/io.aurora.pc")
FILES_PREFIX = b"/data/data/com.android.aura/f"


def relocate_runtime_paths(data: bytes) -> bytes:
    for directory in LEGACY_APP_DIRS:
        source = directory + b"/files"
        assert len(source) == len(FILES_PREFIX)
        data = data.replace(source, FILES_PREFIX)
    if any(directory in data for directory in LEGACY_APP_DIRS):
        raise ValueError("Unknown legacy application path; refusing an incomplete binary relocation")
    return data
