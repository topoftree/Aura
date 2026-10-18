# Third-party notices

Aura includes the third-party components listed below. Their original copyrights and license terms remain applicable; this repository does not relicense them. This is the first public binary distribution in this repository. This notice is not a declaration that all corresponding-source or relinking arrangements have been completed.

| Component | Purpose | Upstream / license |
| --- | --- | --- |
| Winlator 11.2 | Embedded Windows runtime integration, X server, graphics and input | [Winlator](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9), LGPL-2.1; local modifications are included in the binary. |
| Proton Wine 11.0-2 Bionic v14 | Windows compatibility | [Proton Wine](https://github.com/The412Banner/proton-wine), LGPL-2.1-or-later and included component licenses. Modified Wine ntdll and sechost components are included. |
| ntsync-android | Backend included in the Wine build | [ntsync-android](https://github.com/GameNative/ntsync-android/tree/7ce6435e5979b1cb5341aa4b299f31e8937fe121), LGPL-3.0-only. |
| Wine Mono 10.4.1 | Offline .NET Framework replacement | [Wine Mono](https://github.com/wine-mono/wine-mono/releases/tag/wine-mono-10.4.1), mixed LGPL/GPL/MIT and component licenses; see [COPYING](LICENSES/Wine-Mono-COPYING.txt). |
| ICU 68.2 Windows x64 / x86 | Wine globalization dependencies | [ICU](https://github.com/unicode-org/icu/releases/tag/release-68-2), Unicode/ICU and data licenses; upstream license is included as `LICENSE.ICU` in the bundled Windows dependency package. |
| FEXCore 2610 | CPU translation bridges | [FEX](https://github.com/FEX-Emu/FEX/tree/FEX-2610), MIT. |
| DXVK 2.7.1 ARM64EC | Direct3D translation | [DXVK](https://github.com/doitsujin/dxvk), zlib. |
| VKD3D-Proton 3.0.1 ARM64EC | Direct3D 12 translation | [VKD3D-Proton](https://github.com/HansKristian-Work/vkd3d-proton), LGPL-2.1-or-later. |
| Mesa Turnip / Zink | Graphics drivers | [Mesa](https://www.mesa3d.org/) and [community driver builds](https://github.com/K11MCH1/AdrenoToolsDrivers), primarily MIT with per-file licenses; see [Mesa license guide](LICENSES/Mesa-license-guide.rst). |
| Qualcomm Adreno v863.1 community package | Optional offline graphics driver | [Community package](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v863.1_a7xx), proprietary Qualcomm components extracted/adapted by Hugo / StevenMX according to the package metadata. This is not an official Qualcomm distribution or a grant of redistribution rights. |
| AdrenoTools / linkernsbypass | Android graphics loading helpers | [AdrenoTools](https://github.com/bylaws/libadrenotools), BSD-2-Clause; companion license texts are in `LICENSES/`. |
| SDL 2.32.10 | Wine gamepad bridge | [SDL](https://github.com/libsdl-org/SDL/tree/release-2.32.10), zlib; includes a modified input backend. |
| ALSA headers / PulseAudio | Audio integration | [ALSA](https://github.com/alsa-project/alsa-lib/tree/v1.2.14) and [PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/), LGPL-2.1-or-later and component licenses. The Winlator ALSA protocol reference is MIT. |
| GStreamer 1.28.0 media plugins / FFmpeg runtime | Media support | [GStreamer](https://github.com/GStreamer/gstreamer/tree/1.28.0), LGPL-2.1-or-later and per-file notices; FFmpeg and the included native dependencies retain their own upstream licenses. |
| xcb-util-keysyms 0.4.1 / SysVSHM | X11 and shared-memory helpers | X.Org MIT and the retained [SysVSHM license](LICENSES/SysVSHM-LICENSE.txt). |
| Termux native dependencies | Supporting native runtime libraries and utilities | [Pinned native runtime build](https://github.com/GameNative/termux-on-gha/releases/tag/build-20260218), per-component licenses, including LGPL/GPL where applicable. Retained license/copyright files are packaged in the runtime under `usr/share/doc` and `usr/share/licenses`. |
| JavaSteam 1.8.0 | Steam network protocol | [JavaSteam](https://github.com/Longi94/JavaSteam), MIT. |
| AndroidX / Compose / WorkManager / Material / Kotlin libraries | Android application and UI | Apache-2.0 and respective upstream notices. |
| Coil / OkHttp / ZXing / Apache Commons Compress | Images, HTTP, QR codes and archive support | Apache-2.0. |
| Bouncy Castle | Cryptography | Bouncy Castle license, MIT-style. |
| Protocol Buffers | Protocol messages | BSD-3-Clause. |
| XZ for Java | Archive decoding | Public domain for the Java implementation and applicable upstream notices. |
| Zstandard JNI | Archive decoding | BSD-2-Clause for zstd-jni and included Zstandard component terms. |
| Simple Icons: Steam | Steam sign-in mark | [Simple Icons](https://github.com/simple-icons/simple-icons), CC0-1.0; Steam is a Valve trademark. |

License text copies are in [LICENSES](LICENSES/); additional original notices are retained in the APK/runtime packages. These notices do not replace any source-availability, replacement or relinking obligations imposed by a component's license.

## Copyright scope and source availability

Aura's [copyright notice](COPYRIGHT.md) applies only to its original material. It does not change the licenses of the components above, including their modifications, or restrict any applicable rights to source, library replacement, relinking or reverse engineering for debugging library modifications.

A complete corresponding-source and relinking package for all applicable components has not been published with this public release. The existing review of those obligations and of the proprietary Qualcomm package's redistribution rights remains incomplete. Upstream links and license copies provide attribution; they do not by themselves fulfill every redistribution requirement. This documentation update adds no dependencies and makes no claim that those outstanding matters have been resolved.

## Other project names and assets

This APK contains no Pluvia or GameNative application source. Native dependencies whose upstream URLs use those project names retain their own licenses. Steam artwork is fetched from Steam's CDN and belongs to the respective publishers. Game content, fonts, icons and other external assets retain their respective owners' rights and applicable terms; Aura's notice does not relicense them.

Aura is not affiliated with or endorsed by Valve, Ubisoft, Microsoft or the other projects and publishers mentioned here. Product names and trademarks belong to their respective owners.
