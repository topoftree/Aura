<!-- Aura 0.1.82 added integration file; notice dated 2026-10-19. See NOTICE.md and MODIFICATIONS.tsv. -->
# Aura ALSA PCM bridge

`pcm_android_aserver.c` implements the current Winlator 11.2 socket protocol.
It uses the upstream ALSA ioplug public ABI and dynamically links the already
packaged Bionic `libasound.so`. CMake resolves that library from the runtime
manifest and native SHA-256 index; no second libasound is packaged.

Protocol reference: `coffincolors/winlator` at
`49bfb90fce932107cf6a7c1af62f910e7a1a2bfd`,
`audio_plugin/module_pcm_android_aserver.c` (MIT, BrunoSX; licence retained in
`LICENSE.reference`). The legacy plugin is **not wire compatible** with Aura:
Aura reserves four SHM bytes for the pointer and acknowledges a copied WRITE.
The new implementation handles those differences, partial I/O, EOF, no-SIGPIPE,
ring-pointer wrap, pause/resume and finite socket timeouts. Playback only,
interleaved U8 / S16_LE / FLOAT_LE, one or two channels, 8–96 kHz.

The `alsa/` SDK headers are copied verbatim from alsa-lib v1.2.14, except that
`asoundlib.h` joins the upstream head/tail and only the required public includes.
Source: https://github.com/alsa-project/alsa-lib/tree/v1.2.14
Tarball: https://codeload.github.com/alsa-project/alsa-lib/tar.gz/refs/tags/v1.2.14
SHA-256: a7bc6c09f0e5a622ebc8afb63a194aa1396145b5c6433d3445363201d96c23c4
LGPL-2.1-or-later; unmodified COPYING and per-file notices are retained.

Reflector uses Aura's Kotlin virtual PCM clock plus a bounded AudioTrack mirror.
It does not copy Cmod's AAudio pacer implementation or claim identical behavior.
ALSA and Reflector share this guest plugin; their Android output timing differs.

Host wire test (requires a C compiler and the system libasound.so.2):

```sh
cc -Wall -Wextra -Werror -DPIC -Iengine/src/main/cpp/aura_audio tools/tests/alsa_pcm_bridge_test.c -Wl,-l:libasound.so.2 -pthread -o /tmp/alsa-pcm-test
/tmp/alsa-pcm-test
```

The host test exercises the production C callbacks with Unix sockets and passed
file descriptors. It does not test Android hardware audio or Wine negotiation.
