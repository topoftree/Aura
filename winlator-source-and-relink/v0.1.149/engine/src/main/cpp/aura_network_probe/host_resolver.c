/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Fixed-target diagnostic calls in Aura's own process. No interposition, network
 * binding, addresses, DNS payloads or changes to the game's environment. */
#include <android/multinetwork.h>
#include <errno.h>
#include <jni.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdint.h>

static jintArray output(JNIEnv *env, const jint *values, jsize length) {
    jintArray result = (*env)->NewIntArray(env, length);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, length, values);
    return result;
}

static void binding(net_handle_t active, int (*read_binding)(net_handle_t *), jint *out) {
    net_handle_t network = NETWORK_UNSPECIFIED;
    errno = 0;
    int status = read_binding(&network);
    out[0] = status ? errno : 0;
    out[1] = status ? 3 : !network ? 0 : !active ? 3 : network == active ? 1 : 2;
}

JNIEXPORT jintArray JNICALL
Java_com_winlator_core_NetworkHostNativeJni_readBindings(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    jint values[4];
    binding((net_handle_t)handle, android_getprocnetwork, values);
    binding((net_handle_t)handle, android_getprocdns, values + 2);
    return output(env, values, 4);
}

JNIEXPORT jintArray JNICALL
Java_com_winlator_core_NetworkHostNativeJni_query(JNIEnv *env, jobject self, jlong handle,
                                               jint target, jboolean explicit_network) {
    (void)self;
    // EAI, observed errno, v4 count, v6 count, list limit. Never export addresses.
    jint values[5] = {EAI_SYSTEM, EINVAL, 0, 0, 0};
    if (target < 0 || target > 1 || (explicit_network && !handle)) return output(env, values, 5);
    const char *hosts[] = {"store.steampowered.com", "api.steampowered.com"};
    const char *ports[] = {"80", "443"};
    const struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_socktype = SOCK_STREAM,
                                  .ai_protocol = IPPROTO_TCP, .ai_flags = 0};
    struct addrinfo *answers = NULL;
    errno = 0;
    values[0] = explicit_network ? android_getaddrinfofornetwork((net_handle_t)handle,
        hosts[target], ports[target], &hints, &answers) :
        getaddrinfo(hosts[target], ports[target], &hints, &answers);
    values[1] = errno; // Capture before freeaddrinfo or JNI can overwrite it.
    if (!values[0]) {
        struct addrinfo *entry = answers;
        for (int i = 0; entry && i < 64; entry = entry->ai_next, ++i) {
            if (entry->ai_family == AF_INET) ++values[2];
            if (entry->ai_family == AF_INET6) ++values[3];
        }
        values[4] = entry != NULL;
        if (answers) freeaddrinfo(answers);
    }
    return output(env, values, 5);
}
