/* SPDX-License-Identifier: MIT */
#ifndef ZINK_AURA_PRESENT_H
#define ZINK_AURA_PRESENT_H
#include <stdbool.h>
#include <stdint.h>
struct zink_context;
struct zink_resource;
bool zink_aura_present(struct zink_context *, struct zink_resource *, uint32_t window);
void zink_aura_present_destroy(struct zink_context *);
#endif
