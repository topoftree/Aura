/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Aura's PCM bridge for the Winlator ALSA socket protocol. MIT; see README.md.
 * Protocol differs from legacy Cmod: SHM has a 4-byte pointer prefix and every
 * WRITE requires an acknowledgement before the producer may reuse its buffer. */
#include <alsa/asoundlib.h>
#include <alsa/pcm_external.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mman.h>
#include <limits.h>

enum { CLOSE, START, STOP, PAUSE, PREPARE, WRITE, DRAIN, POINTER };
enum { POINTER_BYTES = 4, MAX_BUFFER_BYTES = 1024 * 1024 };
typedef struct {
    snd_pcm_ioplug_t io;
    int fd, frame_bytes;
    unsigned char *shared;
    size_t shared_size;
    uint32_t last_position;
    snd_pcm_uframes_t position, boundary;
} Bridge;

static int send_all(int fd, const void *data, size_t length) {
    const unsigned char *p = data;
    while (length) {
        ssize_t count = send(fd, p, length, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return count < 0 ? -errno : -EPIPE;
        p += count; length -= count;
    }
    return 0;
}

static int receive_all(int fd, void *data, size_t length) {
    unsigned char *p = data;
    while (length) {
        ssize_t count = recv(fd, p, length, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return count < 0 ? -errno : -EPIPE;
        p += count; length -= count;
    }
    return 0;
}

static void put32(unsigned char *p, uint32_t value) {
    for (int i = 0; i < 4; i++) p[i] = (value >> (i * 8)) & 255;
}

static int request(Bridge *b, unsigned char code, uint32_t length) {
    unsigned char header[5] = {code};
    put32(header + 1, length);
    return send_all(b->fd, header, sizeof(header));
}

static int receive_fd(int socket_fd) {
    unsigned char status;
    struct iovec iov = {.iov_base = &status, .iov_len = 1};
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    ssize_t count;
    do { count = recvmsg(socket_fd, &message, MSG_CMSG_CLOEXEC); } while (count < 0 && errno == EINTR);
    if (count != 1) return -2;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&message); c; c = CMSG_NXTHDR(&message, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len == CMSG_LEN(sizeof(int))) {
            int fd; memcpy(&fd, CMSG_DATA(c), sizeof(fd));
            if ((message.msg_flags & MSG_CTRUNC) || status != 0) { close(fd); return -2; }
            return fd;
        }
    }
    return status == 0 ? -1 : -2;
}

static int bridge_close(snd_pcm_ioplug_t *io) {
    Bridge *b = io->private_data;
    if (b->fd >= 0) { request(b, CLOSE, 0); close(b->fd); }
    if (b->shared) munmap(b->shared, b->shared_size);
    free(b);
    return 0;
}
static int bridge_start(snd_pcm_ioplug_t *io) { return request(io->private_data, START, 0); }
static int bridge_stop(snd_pcm_ioplug_t *io) { return request(io->private_data, STOP, 0); }
static int bridge_pause(snd_pcm_ioplug_t *io, int enabled) { return request(io->private_data, enabled ? PAUSE : START, 0); }
static int bridge_drain(snd_pcm_ioplug_t *io) { return request(io->private_data, DRAIN, 0); }
static int bridge_sw_params(snd_pcm_ioplug_t *io, snd_pcm_sw_params_t *params) {
    Bridge *b = io->private_data;
    return snd_pcm_sw_params_get_boundary(params, &b->boundary);
}

static int bridge_prepare(snd_pcm_ioplug_t *io) {
    Bridge *b = io->private_data;
    b->last_position = 0; b->position = 0;
    b->frame_bytes = snd_pcm_format_physical_width(io->format) * io->channels / 8;
    if (b->frame_bytes <= 0 || io->buffer_size > MAX_BUFFER_BYTES / (unsigned)b->frame_bytes) return -EINVAL;
    unsigned char args[10] = {(unsigned char)io->channels};
    switch (io->format) {
        case SND_PCM_FORMAT_U8: args[1] = 0; break;
        case SND_PCM_FORMAT_S16_LE: args[1] = 1; break;
        case SND_PCM_FORMAT_FLOAT_LE: args[1] = 3; break;
        default: return -EINVAL;
    }
    put32(args + 2, io->rate); put32(args + 6, io->buffer_size);
    if (b->shared) { munmap(b->shared, b->shared_size); b->shared = NULL; }
    int error = request(b, PREPARE, sizeof(args));
    if (error < 0 || (error = send_all(b->fd, args, sizeof(args))) < 0) return error;
    // The bundled host always sends the handshake. A failed SHM allocation falls
    // back to socket PCM; a local mmap failure aborts instead of corrupting framing.
    int shared_fd = receive_fd(b->fd);
    if (shared_fd == -2) return -EPIPE;
    if (shared_fd >= 0) {
        b->shared_size = io->buffer_size * b->frame_bytes + POINTER_BYTES;
        void *memory = mmap(NULL, b->shared_size, PROT_READ | PROT_WRITE, MAP_SHARED, shared_fd, 0);
        close(shared_fd);
        if (memory == MAP_FAILED) return -ENOMEM;
        b->shared = memory;
    }
    return 0;
}

static snd_pcm_uframes_t advance_position(Bridge *b, uint32_t position) {
    // Report at ALSA's large boundary, not buffer_size: consuming an entire
    // buffer between polls must not look like zero progress. Extend the host's
    // 32-bit counter through wraparound for long-running sessions.
    uint32_t delta = position - b->last_position;
    b->last_position = position;
    b->position = (b->position + delta) % b->boundary;
    return b->position;
}

static snd_pcm_sframes_t bridge_pointer(snd_pcm_ioplug_t *io) {
    Bridge *b = io->private_data;
    unsigned char response[4];
    int error = request(b, POINTER, 0);
    if (error < 0 || (error = receive_all(b->fd, response, sizeof(response))) < 0) return error;
    uint32_t position = (uint32_t)response[0] | (uint32_t)response[1] << 8 |
        (uint32_t)response[2] << 16 | (uint32_t)response[3] << 24;
    return advance_position(b, position);
}

static snd_pcm_sframes_t bridge_transfer(snd_pcm_ioplug_t *io, const snd_pcm_channel_area_t *areas,
                                         snd_pcm_uframes_t offset, snd_pcm_uframes_t frames) {
    Bridge *b = io->private_data;
    if (frames > io->buffer_size || frames > MAX_BUFFER_BYTES / (unsigned)b->frame_bytes) return -EINVAL;
    if (areas->step != (unsigned)b->frame_bytes * 8 || (areas->first % 8)) return -EINVAL;
    size_t bytes = frames * b->frame_bytes;
    const unsigned char *data = (const unsigned char *)areas->addr + areas->first / 8 + offset * b->frame_bytes;
    if (b->shared) memcpy(b->shared + POINTER_BYTES, data, bytes);
    int error = request(b, WRITE, bytes);
    if (error < 0) return error;
    if (b->shared) {
        unsigned char ack;
        error = receive_all(b->fd, &ack, 1);
        if (error < 0) return error;
        if (ack != 1) return -EIO;
    } else if ((error = send_all(b->fd, data, bytes)) < 0) return error;
    return frames;
}

static const snd_pcm_ioplug_callback_t callbacks = {
    .close = bridge_close, .start = bridge_start, .stop = bridge_stop, .pause = bridge_pause,
    .resume = bridge_start, .prepare = bridge_prepare, .transfer = bridge_transfer,
    .drain = bridge_drain, .pointer = bridge_pointer, .sw_params = bridge_sw_params
};

static int constraints(snd_pcm_ioplug_t *io) {
    const unsigned access[] = {SND_PCM_ACCESS_RW_INTERLEAVED};
    const unsigned formats[] = {SND_PCM_FORMAT_U8, SND_PCM_FORMAT_S16_LE, SND_PCM_FORMAT_FLOAT_LE};
    int error = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_ACCESS, 1, access);
    if (error < 0) return error;
    error = snd_pcm_ioplug_set_param_list(io, SND_PCM_IOPLUG_HW_FORMAT, 3, formats);
    if (error < 0) return error;
    const unsigned limits[][3] = {
        {SND_PCM_IOPLUG_HW_CHANNELS, 1, 2}, {SND_PCM_IOPLUG_HW_RATE, 8000, 96000},
        {SND_PCM_IOPLUG_HW_PERIOD_BYTES, 64, 65536}, {SND_PCM_IOPLUG_HW_BUFFER_BYTES, 128, MAX_BUFFER_BYTES},
        {SND_PCM_IOPLUG_HW_PERIODS, 2, 64}
    };
    for (unsigned i = 0; i < sizeof(limits) / sizeof(limits[0]); i++) {
        error = snd_pcm_ioplug_set_param_minmax(io, limits[i][0], limits[i][1], limits[i][2]);
        if (error < 0) return error;
    }
    return 0;
}

SND_PCM_PLUGIN_DEFINE_FUNC(android_aserver) {
    (void)root; (void)conf;
    if (stream != SND_PCM_STREAM_PLAYBACK) return -ENOTSUP;
    const char *path = getenv("ANDROID_ALSA_SERVER");
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (!path || strlen(path) >= sizeof(address.sun_path)) return -EINVAL;
    memcpy(address.sun_path, path, strlen(path) + 1);
    Bridge *b = calloc(1, sizeof(*b));
    if (!b) return -ENOMEM;
    b->fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    int error = -errno;
    if (b->fd < 0) { free(b); return error; }
    struct timeval timeout = {.tv_sec = 5};
    setsockopt(b->fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(b->fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    int result;
    do { result = connect(b->fd, (struct sockaddr *)&address, sizeof(address)); } while (result < 0 && errno == EINTR);
    if (result < 0) { error = -errno; close(b->fd); free(b); return error; }
    b->io.version = SND_PCM_IOPLUG_VERSION;
    b->io.name = "Aura Android ALSA";
    b->io.callback = &callbacks;
    b->io.private_data = b;
    b->io.flags = SND_PCM_IOPLUG_FLAG_BOUNDARY_WA;
    b->boundary = LONG_MAX; // Replaced by ALSA's sw_params before playback.
    b->io.poll_fd = b->fd;
    b->io.poll_events = POLLOUT;
    error = snd_pcm_ioplug_create(&b->io, name, stream, mode);
    if (error < 0) { close(b->fd); free(b); return error; }
    error = constraints(&b->io);
    if (error < 0) { snd_pcm_ioplug_delete(&b->io); return error; }
    *pcmp = b->io.pcm;
    return 0;
}
SND_PCM_PLUGIN_SYMBOL(android_aserver);
