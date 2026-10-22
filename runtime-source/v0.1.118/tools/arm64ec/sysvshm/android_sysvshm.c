/* Winlator android_sysvshm client, hardened by Aura for interrupted I/O and fd lifetime.
 * Protocol and upstream MIT licence are preserved; see LICENSE and sources.lock.json. */
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <sys/shm.h>
#include <stddef.h>

/* This client is loaded by Bionic libraries. Use the NDK's shmid_ds, not the
 * old glibc/Winlator layout (which would overwrite a Bionic IPC_STAT buffer). */
#if defined(__ANDROID__) && defined(__aarch64__)
_Static_assert(sizeof(struct shmid_ds) == 112, "Bionic AArch64 shmid_ds ABI");
_Static_assert(offsetof(struct shmid_ds, shm_nattch) == 88, "Bionic attach count ABI");
#endif

typedef struct {
    int id, fd, removed;
    void *address;
    size_t size;
    unsigned references;
} segment;
static segment *segments;
static size_t count;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;

static int connect_server(void) {
    const char *path = getenv("ANDROID_SYSVSHM_SERVER");
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (!path || !*path || strlen(path) >= sizeof(address.sun_path)) { errno = EINVAL; return -1; }
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    strcpy(address.sun_path, path);
    int result;
    do { result = connect(fd, (struct sockaddr *)&address, sizeof(address)); } while (result < 0 && errno == EINTR);
    if (result < 0) { int error = errno; close(fd); errno = error; return -1; }
    return fd;
}
static int request(int fd, uint8_t operation, uint32_t value) {
    unsigned char data[5] = {operation, value, value >> 8, value >> 16, value >> 24};
    for (size_t sent = 0; sent < sizeof(data);) {
        ssize_t n = send(fd, data + sent, sizeof(data) - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        sent += (size_t)n;
    }
    return 0;
}
static int receive_id(int fd) {
    unsigned char data[4];
    for (size_t received = 0; received < sizeof(data);) {
        ssize_t n = read(fd, data + received, sizeof(data) - received);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { errno = EIO; return -1; }
        received += (size_t)n;
    }
    uint32_t id = (uint32_t)data[0] | (uint32_t)data[1] << 8 | (uint32_t)data[2] << 16 | (uint32_t)data[3] << 24;
    if (id == 0 || id > INT_MAX) { errno = EIO; return -1; }
    return (int)id;
}
static int receive_fd(int fd) {
    char data;
    struct iovec io = {.iov_base = &data, .iov_len = 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    ssize_t n;
    do { n = recvmsg(fd, &message, MSG_CMSG_CLOEXEC); } while (n < 0 && errno == EINTR);
    if (n != 1 || (message.msg_flags & MSG_CTRUNC)) { errno = EIO; return -1; }
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    if (!c || c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS || c->cmsg_len != CMSG_LEN(sizeof(int))) { errno = EIO; return -1; }
    int result;
    memcpy(&result, CMSG_DATA(c), sizeof(result));
    return result;
}
static int index_of(int id) {
    for (size_t i = 0; i < count; ++i) if (segments[i].id == id) return (int)i;
    return -1;
}
static void remove_remote(int id) {
    int fd = connect_server();
    if (fd >= 0) { request(fd, 2, (uint32_t)id); close(fd); }
}
static void remove_at(size_t i) {
    remove_remote(segments[i].id);
    close(segments[i].fd);
    memmove(&segments[i], &segments[i + 1], (--count - i) * sizeof(segment));
}
int shmget(key_t key, size_t size, int flags) {
    (void)flags;
    if (key != IPC_PRIVATE || !size || size > INT_MAX) { errno = EINVAL; return -1; }
    pthread_mutex_lock(&mutex);
    int server = connect_server(), id = -1, fd = -1;
    if (server >= 0 && request(server, 0, (uint32_t)size) == 0) id = receive_id(server);
    if (id >= 0 && request(server, 1, (uint32_t)id) == 0) fd = receive_fd(server);
    if (server >= 0) close(server);
    segment *grown = fd < 0 ? NULL : realloc(segments, (count + 1) * sizeof(segment));
    if (!grown) {
        if (fd >= 0) close(fd);
        if (id >= 0) remove_remote(id);
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    segments = grown;
    size_t page = (size_t)getpagesize();
    segments[count++] = (segment){.id = id, .fd = fd, .size = (size + page - 1) / page * page};
    pthread_mutex_unlock(&mutex);
    return id;
}
void *shmat(int id, const void *address, int flags) {
    (void)address;
    pthread_mutex_lock(&mutex);
    int i = index_of(id);
    void *result = MAP_FAILED;
    if (i >= 0) {
        segment *s = &segments[i];
        if (!s->address) {
            void *mapped = mmap(NULL, s->size, PROT_READ | ((flags & SHM_RDONLY) ? 0 : PROT_WRITE), MAP_SHARED, s->fd, 0);
            if (mapped != MAP_FAILED) s->address = mapped;
        }
        if (s->address) { result = s->address; s->references++; }
    } else errno = EINVAL;
    pthread_mutex_unlock(&mutex);
    return result;
}
int shmdt(const void *address) {
    pthread_mutex_lock(&mutex);
    int result = -1;
    for (size_t i = 0; i < count; ++i) if (address && segments[i].address == address) {
        result = 0;
        if (--segments[i].references == 0) {
            munmap(segments[i].address, segments[i].size);
            segments[i].address = NULL;
            if (segments[i].removed) remove_at(i);
        }
        break;
    }
    if (result < 0) errno = EINVAL;
    pthread_mutex_unlock(&mutex);
    return result;
}
int shmctl(int id, int command, struct shmid_ds *buffer) {
    pthread_mutex_lock(&mutex);
    int i = index_of(id), result = -1;
    if (i >= 0 && command == IPC_RMID) {
        if (segments[i].address) segments[i].removed = 1; else remove_at((size_t)i);
        result = 0;
    } else if (i >= 0 && command == IPC_STAT && buffer) {
        memset(buffer, 0, sizeof(*buffer));
        buffer->shm_segsz = segments[i].size;
        buffer->shm_nattch = segments[i].references;
#ifdef __ANDROID__
        buffer->shm_perm.key = IPC_PRIVATE;
#else
        buffer->shm_perm.__key = IPC_PRIVATE;
#endif
        buffer->shm_perm.uid = buffer->shm_perm.cuid = geteuid();
        buffer->shm_perm.gid = buffer->shm_perm.cgid = getegid();
        buffer->shm_perm.mode = 0600;
        result = 0;
    }
    if (result < 0) errno = EINVAL;
    pthread_mutex_unlock(&mutex);
    return result;
}

/* Mesa's Android builds import these names instead of Bionic's blocked SysV
 * syscalls. Aliases share the very same segment table and avoid a PLT call
 * accidentally resolving shmget/shmat/... to libc or another shim. */
#if defined(__GNUC__) && !defined(__clang__)
#define SHM_ALIAS(name) __attribute__((alias(#name), copy(name)))
#else
#define SHM_ALIAS(name) __attribute__((alias(#name)))
#endif
extern __typeof(shmget) libandroid_shmget SHM_ALIAS(shmget);
extern __typeof(shmat) libandroid_shmat SHM_ALIAS(shmat);
extern __typeof(shmdt) libandroid_shmdt SHM_ALIAS(shmdt);
extern __typeof(shmctl) libandroid_shmctl SHM_ALIAS(shmctl);
