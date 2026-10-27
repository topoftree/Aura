/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#define _GNU_SOURCE
#include "dri3_hardware_buffer.h"
#include <stdbool.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <unistd.h>

static bool local_stream(int fd) {
    int type = 0;
    socklen_t length = sizeof(type);
    struct sockaddr_un address;
    socklen_t address_length = sizeof(address);
    struct ucred peer;
    socklen_t peer_length = sizeof(peer);
    return fd >= 0 && getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &length) == 0 &&
        type == SOCK_STREAM && getpeername(fd, (struct sockaddr *)&address, &address_length) == 0 &&
        address.sun_family == AF_UNIX &&
        getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_length) == 0 && peer.uid == getuid();
}

void aura_dri3_abort_hardware_buffer(int fd) {
    /* close alone cannot wake this Wrapper: it retains another copy of the
     * receiving endpoint until its one-byte acknowledgment has arrived. */
    if (local_stream(fd)) shutdown(fd, SHUT_RDWR);
}

AHardwareBuffer *aura_dri3_receive_hardware_buffer(int fd, uint32_t width, uint32_t height) {
    AHardwareBuffer *buffer = NULL;
    struct timeval receive_timeout, send_timeout;
    socklen_t length = sizeof(receive_timeout);
    bool receive_saved = false, send_saved = false;
    if (!local_stream(fd)) return NULL;
    if (!width || !height || width > 8192 || height > 8192 ||
        (uint64_t)width * height > UINT64_C(33554432)) goto failed;
    if (getsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, &length)) goto failed;
    receive_saved = true;
    length = sizeof(send_timeout);
    if (getsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, &length)) goto failed;
    send_saved = true;
    const struct timeval budget = {.tv_sec = 0, .tv_usec = 250000};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &budget, sizeof(budget)) ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &budget, sizeof(budget))) goto failed;

    if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &buffer) || !buffer) goto failed;
    AHardwareBuffer_Desc desc = {0};
    AHardwareBuffer_describe(buffer, &desc);
    /* RGBA/RGBX/BGRA are the Wrapper's 32-bit X11 image formats. Import as an
     * EGL image; never lock a game-owned GPU buffer for CPU writes. */
    if (desc.width != width || desc.height != height || desc.layers != 1 ||
        desc.stride < width || desc.stride > 32767 ||
        (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM &&
         desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM && desc.format != 5) ||
        !(desc.usage & (AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT)) ||
        (desc.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT)) goto failed;
    const uint8_t accepted = 1;
    if (send(fd, &accepted, 1, MSG_NOSIGNAL) != 1) goto failed;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout));
    return buffer;

failed:
    if (buffer) AHardwareBuffer_release(buffer);
    if (receive_saved) setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));
    if (send_saved) setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout));
    aura_dri3_abort_hardware_buffer(fd);
    return NULL;
}
