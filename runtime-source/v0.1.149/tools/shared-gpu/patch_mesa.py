#!/usr/bin/env python3
"""Apply the small Aura bridge to the pinned, otherwise unmodified Mesa source."""
from pathlib import Path
import argparse
import shutil

ROOT = Path(__file__).resolve().parents[2]

def patch(source):
    zink = source / 'src/gallium/drivers/zink'
    def replace(path, old, new):
        text = path.read_text()
        if new in text:
            return
        assert text.count(old) == 1, path
        path.write_text(text.replace(old, new))
    for name in ('zink_aura_present.c', 'zink_aura_present.h'):
        shutil.copyfile(Path(__file__).parent / name, zink / name)
    shutil.copyfile(ROOT / 'engine/src/main/cpp/winlator/include/shared_gpu_protocol.h', zink / 'shared_gpu_protocol.h')
    replace(zink/'meson.build', "  'zink_batch.c',", "  'zink_aura_present.c',\n  'zink_batch.c',")
    replace(zink/'zink_device_info.py', '    Extension("VK_KHR_external_memory"),',
            '    Extension("VK_ANDROID_external_memory_android_hardware_buffer"),\n    Extension("VK_KHR_external_memory"),')
    # Gallium callbacks cast pipe_context* straight back to zink_context*.
    # Keep the base at offset zero; migrate an already patched build tree too.
    types = zink / 'zink_types.h'
    legacy = 'struct zink_context {\n   void *aura_present;\n   bool aura_present_disabled;'
    if legacy in types.read_text():
        types.write_text(types.read_text().replace(legacy, 'struct zink_context {'))
    replace(types, 'struct zink_context {\n   struct pipe_context base;',
            'struct zink_context {\n   struct pipe_context base;\n   void *aura_present;\n   bool aura_present_disabled;')
    for name in ('zink_context.c','zink_screen.c'):
        p=zink/name
        replace(p, '#include "zink_context.h"', '#include "zink_context.h"\n#include "zink_aura_present.h"')
    replace(zink/'zink_context.c', '#include "zink_aura_present.h"',
            '#include "zink_aura_present.h"\n#include <stddef.h>\n'
            '_Static_assert(offsetof(struct zink_context, base) == 0, "Gallium context base must stay first");')
    replace(zink/'zink_context.c', '   for (unsigned i = 0; i < ARRAY_SIZE(ctx->program_cache); i++) {',
            '   zink_aura_present_destroy(ctx);\n\n   for (unsigned i = 0; i < ARRAY_SIZE(ctx->program_cache); i++) {')
    replace(zink/'zink_screen.c', '#include "zink_aura_present.h"',
            '#include "zink_aura_present.h"\n#include "frontend/xlibsw_api.h"')
    replace(zink/'zink_screen.c', '   void *map = winsys->displaytarget_map(winsys, res->dt, 0);', '''   /* A complete ordinary 24-bit Xlib surface only. Unsupported cases retain
    * the original transfer/map/SHM path below. Never map before trying AHB. */
   if (pctx && winsys_drawable_handle && level == 0 && layer == 0 &&
       (nboxes == 0 || (nboxes == 1 && sub_box && sub_box->x == 0 && sub_box->y == 0 &&
        sub_box->width == pres->width0 && sub_box->height == pres->height0))) {
      const struct xlib_drawable *drawable = winsys_drawable_handle;
      struct zink_context *ctx = zink_tc_context_unwrap(pctx, screen->threaded);
      if (drawable->depth == 24 && zink_aura_present(ctx, res, drawable->drawable))
         return;
   }
   void *map = winsys->displaytarget_map(winsys, res->dt, 0);''')
    p=source/'src/gallium/targets/libgl-xlib/meson.build'
    replace(p, 'gallium_xlib_ld_args = []', "gallium_xlib_ld_args = ['-landroid', '-landroid-sysvshm']")
    # Normal XMesaSwapBuffers calls display with st=NULL. Recover only the
    # current thread's matching context, not the fork's process-global hack.
    p=source/'src/gallium/frontends/glx/xlib/xm_st.c'
    replace(p, '   struct pipe_context *pctx = st ? st->pipe : NULL;', '''   if (!st) {
      XMesaContext current = XMesaGetCurrentContext();
      if (current && current->xm_buffer == xstfb->buffer)
         st = current->st;
   }
   struct pipe_context *pctx = st ? st->pipe : NULL;''')

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('source', type=Path)
    patch(parser.parse_args().source)
