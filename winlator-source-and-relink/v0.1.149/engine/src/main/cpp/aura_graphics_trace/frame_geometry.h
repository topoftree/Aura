/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Coalesce duplicate XGetGeometry within one GL swap, never across frames.
 * No request may have been issued and no X event may be queued since the
 * fresh reply. Unknown delegates, nested swaps and failures always fall back.
 */
#ifndef AURA_FRAME_GEOMETRY_H
#define AURA_FRAME_GEOMETRY_H
#include <errno.h>
typedef unsigned long (*aura_next_request_fn)(void *);
typedef int (*aura_events_queued_fn)(void *, int);
typedef int (*aura_geometry_fn)(void *, unsigned long, unsigned long *, int *, int *,
                               unsigned *, unsigned *, unsigned *, unsigned *);
struct aura_frame_geometry {
    unsigned nesting;
    int valid, result;
    void *display;
    unsigned long drawable, serial, root;
    int x, y;
    unsigned width, height, border, bits;
};
static inline void aura_geometry_enter(struct aura_frame_geometry *g) {
    ++g->nesting;
    g->valid = 0;
}
static inline void aura_geometry_leave(struct aura_frame_geometry *g) {
    if (g->nesting) --g->nesting;
    g->valid = 0;
}
static inline int aura_geometry_query(struct aura_frame_geometry *g, aura_geometry_fn query,
        aura_next_request_fn next, aura_events_queued_fn events, void *display,
        unsigned long drawable, unsigned long *root, int *x, int *y,
        unsigned *width, unsigned *height, unsigned *border, unsigned *bits, int *hit) {
    *hit = 0;
    if (g->nesting != 1 || !next || !events || !root || !x || !y || !width || !height || !border || !bits)
        return query(display, drawable, root, x, y, width, height, border, bits);
    int saved = errno;
    /* QueuedAfterReading reads already available events without flushing
     * requests or waiting for a round trip. Never consume/discard an event. */
    if (g->valid && g->display == display && g->drawable == drawable &&
            events(display, 1) == 0 && next(display) == g->serial) {
        *root = g->root; *x = g->x; *y = g->y;
        *width = g->width; *height = g->height; *border = g->border; *bits = g->bits;
        *hit = 1;
        errno = saved;
        return g->result;
    }
    g->valid = 0;
    unsigned long before = next(display);
    errno = saved;
    int result = query(display, drawable, root, x, y, width, height, border, bits);
    saved = errno;
    unsigned long after = next(display);
    /* Cache only a successful, nonempty snapshot with an empty event queue. */
    if (result && *width && *height && after == before + 1 && events(display, 1) == 0 && next(display) == after) {
        g->display = display; g->drawable = drawable; g->serial = after;
        g->root = *root; g->x = *x; g->y = *y; g->width = *width; g->height = *height;
        g->border = *border; g->bits = *bits; g->result = result; g->valid = 1;
    }
    errno = saved;
    return result;
}
#endif
