# Winlator engine source and recombination materials — Aura 0.1.83

Winlator is copyright its original authors and contributors. The upstream base is
[Winlator 11.2, commit 3981d86efa4f333b2a34a7da8b6521476cd8c8b9](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9).
The original [GNU Lesser General Public License, version 2.1](engine/LICENSE)
is retained without alteration.

The `engine/` directory contains the Winlator-derived Java/Kotlin, JNI/native
integration, Android resources and build definitions used by Aura 0.1.83.
Aura's contributions to that derivative engine are supplied under LGPL 2.1.
Independent components within the engine retain their own file-level licenses;
this statement does not replace those licenses or claim their copyrights.

`MODIFICATIONS.tsv` records the file-level comparison against the pinned upstream
tree. `UNCHANGED` means identical original bytes; `MODIFIED` and `ADDED` identify
the local integration. Its SHA-256 column describes the production-source bytes
before the added publication notices. Modified source files carry a notice dated
2026-10-19. The engine implementation is identical to 0.1.82; existing publication
notices referring to that version are retained because they describe the same code. Original copyright and license notices are preserved. The standalone
build scripts are adapted for this recombination kit on that date.

The changes include Aura's Android/ARM64EC runtime integration; game session,
process, input, audio, display and diagnostics support; dependency verification;
and local compatibility changes. These are supplied as editable source, not
merely as links to an unmodified upstream release.

## Different terms for application objects

`objects/aura-1.jar` contains Aura's independent application **object code**, with
class/member obfuscation and debug/Kotlin metadata removal performed before the
final combined R8 release build. It does not contain the editable Kotlin
application source. The Winlator-derived source remains editable under its
applicable licenses. The dependency JARs contain
third-party object code under their original licenses. Compiled Android resources,
resource IDs, the package manifest and optimization rules are build inputs.
They are not a grant of an open-source license over Aura's independent application.
The private name mapping is not needed to modify and rebuild the supplied engine.
Obfuscation does not cancel any of the permissions below or prevent decompilation.

To the extent of Aura's rights, recipients may copy and redistribute these
recombination materials with these notices, modify the combined work for their
own use, and combine/relink the application object code with a modified version
of the LGPL engine. Reverse engineering of the combined work for debugging
modifications to the LGPL components is expressly permitted. These permissions
prevail over an inconsistent restriction in an Aura copyright notice or EULA.
No term here restricts the separate modification or redistribution rights granted
by the LGPL or another third-party license.

No original production signing key is supplied or needed to build and sign a
modified copy with the recipient's own key. See `README.md` for the installation
limitations of using a different Android signing key.

## Scope and outstanding work

This kit addresses the modified **Winlator Android engine and its recombination
with Aura 0.1.83**. It does not purport to be complete corresponding source for
every separately bundled Wine, ntsync, Mono, media/audio or other runtime binary.
Those components' source obligations remain separate. Proprietary driver
redistribution permissions are also separate. See the repository's
`THIRD_PARTY_NOTICES.md` and `SOURCE_CODE.md` for the remaining items.

Retaining independent Aura source privately is conditional on a valid boundary
between independent application code and LGPL-derived code. This technical kit
is not a legal opinion certifying that boundary or the entire APK.
