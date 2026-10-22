# Winlator source and relinking kits

Winlator engine source and build materials for rebuilding Aura with a modified
engine. Independent Aura application code is supplied as pre-obfuscated objects.

Choose the kit that matches your Aura version:

| Aura version | Source and build instructions | Official release |
| --- | --- | --- |
| 0.1.118 | [Open the 0.1.118 kit on main](v0.1.118/README.md) | [APK and checksum](https://github.com/topoftree/Aura/releases/tag/v0.1.118) |
| 0.1.101 | [Open the 0.1.101 kit](v0.1.101/README.md) | [APK](https://github.com/topoftree/Aura/releases/tag/v0.1.101) |
| 0.1.83 | [Open the 0.1.83 kit](v0.1.83/README.md) | [APK and standalone kit ZIP](https://github.com/topoftree/Aura/releases/tag/v0.1.83) |
| 0.1.82 | [Open the 0.1.82 kit](v0.1.82/README.md) | [APK](https://github.com/topoftree/Aura/releases/tag/v0.1.82) |

Each version includes:

- `engine/`: editable Winlator engine source, including Aura's modifications and
  the original license and attribution notices.
- `objects/`: pre-obfuscated independent Aura application objects, dependency
  objects and compiled resources needed to rebuild the combined application.
- Build scripts, file checksums, modification records, permissions and validation
  results. Follow that version's README to build and sign a modified APK.

The LGPL-covered engine remains readable source. Independent Aura Kotlin UI and
business source, private name mappings and production signing keys are not
included. Obfuscated object code can still be inspected and decompiled; read each
kit's `NOTICE.md` and `PRIVACY.md` for the permissions and limitations. See
[source availability](../SOURCE_CODE.md) for the scope and outstanding obligations
of other components.

This directory was previously named `relink/`. The kits on the current branch now
use `winlator-source-and-relink/`; existing release tags and downloaded archives
retain their original paths and filenames. Use the instructions included with
the checkout or archive you are using.
