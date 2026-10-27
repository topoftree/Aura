/* Aura bounded, anonymous connectivity control. No Steam/account APIs or response bodies.
 * Build: build.py. The parent owns a 20-second deadline and kills this process on cancellation. */
typedef unsigned long DWORD;
typedef unsigned short WORD, WCHAR;
typedef unsigned long long SIZE, SOCKET;
typedef int BOOL;
typedef void *HANDLE;
#define API __declspec(dllimport) __stdcall
API HANDLE GetStdHandle(DWORD);
API BOOL WriteFile(HANDLE, const void *, DWORD, DWORD *, void *);
API DWORD GetLastError(void);
API unsigned long long GetTickCount64(void);
API void ExitProcess(DWORD);
API int WSAStartup(WORD, void *);
API int WSACleanup(void);
API int WSAGetLastError(void);
struct address { int flags, family, type, protocol; SIZE length; char *canon; void *address; struct address *next; };
API int getaddrinfo(const char *, const char *, const struct address *, struct address **);
API void freeaddrinfo(struct address *);
API SOCKET socket(int, int, int);
API int ioctlsocket(SOCKET, long, unsigned long *);
API int connect(SOCKET, const void *, int);
struct fdset { unsigned int count; SOCKET sockets[1]; };
struct timeval { long seconds, micros; };
API int select(int, void *, struct fdset *, struct fdset *, struct timeval *);
API int getsockopt(SOCKET, int, int, char *, int *);
API int closesocket(SOCKET);
API HANDLE WinHttpOpen(const WCHAR *, DWORD, const WCHAR *, const WCHAR *, DWORD);
API BOOL WinHttpSetTimeouts(HANDLE, int, int, int, int);
API BOOL WinHttpSetOption(HANDLE, DWORD, void *, DWORD);
API HANDLE WinHttpConnect(HANDLE, const WCHAR *, WORD, DWORD);
API HANDLE WinHttpOpenRequest(HANDLE, const WCHAR *, const WCHAR *, const WCHAR *, const WCHAR *, const WCHAR **, DWORD);
API BOOL WinHttpSendRequest(HANDLE, const WCHAR *, DWORD, void *, DWORD, DWORD, SIZE);
API BOOL WinHttpReceiveResponse(HANDLE, void *);
API BOOL WinHttpQueryHeaders(HANDLE, DWORD, const WCHAR *, void *, DWORD *, DWORD *);
API BOOL WinHttpCloseHandle(HANDLE);
static unsigned long long started;
static void append(char *s, int *p, const char *v) { while (*v && *p < 250) s[(*p)++] = *v++; }
static void number(char *s, int *p, long long v) {
    char digits[24]; int n = 0;
    if (v < 0) { s[(*p)++] = '-'; v = -v; }
    do { digits[n++] = (char)('0' + v % 10); v /= 10; } while (v);
    while (n) s[(*p)++] = digits[--n];
}
static void emit(const char *target, const char *stage, long long code, long long value, int family) {
    char out[320]; int p = 0; DWORD written;
    append(out, &p, "NET_PROBE_V1 target="); append(out, &p, target);
    append(out, &p, " stage="); append(out, &p, stage);
    append(out, &p, " code="); number(out, &p, code);
    append(out, &p, " value="); number(out, &p, value);
    append(out, &p, " family="); number(out, &p, family);
    append(out, &p, " ms="); number(out, &p, (long long)(GetTickCount64() - started));
    out[p++] = '\n'; WriteFile(GetStdHandle((DWORD)-11), out, (DWORD)p, &written, 0);
}
static void tcp(const char *label, struct address *a) {
    SOCKET s = socket(a->family, 1, 6);
    if (s == (SOCKET)-1) { emit(label, "SOCKET", WSAGetLastError(), 0, a->family); return; }
    unsigned long nonblocking = 1;
    if (ioctlsocket(s, (long)0x8004667e, &nonblocking)) { emit(label, "NONBLOCK", WSAGetLastError(), 0, a->family); closesocket(s); return; }
    emit(label, "CONNECT_BEGIN", 0, 0, a->family);
    int rc = connect(s, a->address, (int)a->length);
    int error = rc ? WSAGetLastError() : 0;
    emit(label, "CONNECT", error, rc, a->family);
    if (rc && (error == 10035 || error == 10036)) {
        struct fdset writable = {1, {s}}, exceptional = {1, {s}};
        struct timeval timeout = {2, 0};
        rc = select(0, 0, &writable, &exceptional, &timeout);
        error = rc < 0 ? WSAGetLastError() : 0;
        emit(label, "SELECT", error, rc, a->family);
        if (rc > 0) {
            int socketError = 0, size = sizeof(socketError);
            rc = getsockopt(s, 0xffff, 0x1007, (char *)&socketError, &size);
            emit(label, "SO_ERROR", rc ? WSAGetLastError() : socketError, rc, a->family);
        }
    }
    closesocket(s);
}
static void probe(const char *label, const char *host, const WCHAR *wideHost, const WCHAR *path, WORD port) {
    struct address hints = {0, 0, 1, 6, 0, 0, 0, 0}, *addresses = 0;
    emit(label, "DNS_BEGIN", 0, 0, 0);
    int code = getaddrinfo(host, port == 443 ? "443" : "80", &hints, &addresses);
    int count = 0, ipv4 = 0, ipv6 = 0, zero = 0;
    for (struct address *a = addresses; a && count < 64; a = a->next) {
        count++; ipv4 += a->family == 2; ipv6 += a->family == 23;
        if (a->family == 2 && a->length >= 8) {
            unsigned char *p = (unsigned char *)a->address;
            zero += !(p[4] | p[5] | p[6] | p[7]);
        }
    }
    emit(label, "DNS", code, count, 0);
    emit(label, "DNS_V4", 0, ipv4, 2); emit(label, "DNS_V6", 0, ipv6, 23); emit(label, "DNS_ZERO", 0, zero, 0);
    int tested4 = 0, tested6 = 0;
    count = 0;
    for (struct address *a = addresses; a && count++ < 64; a = a->next) {
        if (a->family == 2 && !tested4) { tested4 = 1; tcp(label, a); }
        if (a->family == 23 && !tested6) { tested6 = 1; tcp(label, a); }
    }
    if (addresses) freeaddrinfo(addresses);
    /* NO_PROXY is an explicit control; it does not claim to replicate Steam's proxy/HTTP implementation. */
    HANDLE session = WinHttpOpen(L"AuraNetworkDiagnostic/1", 1, 0, 0, 0);
    emit(label, "HTTP_OPEN", session ? 0 : GetLastError(), 0, 0);
    if (!session) return;
    HANDLE connection = 0, request = 0;
    if (!WinHttpSetTimeouts(session, 2000, 2000, 2000, 2000)) { emit(label, "HTTP_TIMEOUTS", GetLastError(), 0, 0); goto done; }
    connection = WinHttpConnect(session, wideHost, port, 0);
    emit(label, "HTTP_CONNECT_HANDLE", connection ? 0 : GetLastError(), 0, 0);
    if (!connection) goto done;
    request = WinHttpOpenRequest(connection, L"GET", path, 0, 0, 0, port == 443 ? 0x800000 : 0);
    emit(label, "HTTP_REQUEST", request ? 0 : GetLastError(), 0, 0);
    if (!request) goto done;
    DWORD disabled = 3; /* WINHTTP_DISABLE_COOKIES | WINHTTP_DISABLE_REDIRECTS */
    DWORD autologon = 2; /* WINHTTP_AUTOLOGON_SECURITY_LEVEL_HIGH */
    if (!WinHttpSetOption(request, 63, &disabled, sizeof(disabled)) ||
        !WinHttpSetOption(request, 77, &autologon, sizeof(autologon))) {
        emit(label, "HTTP_OPTIONS", GetLastError(), 0, 0); goto done;
    }
    emit(label, "HTTP_SEND_BEGIN", 0, 0, 0);
    BOOL ok = WinHttpSendRequest(request, 0, 0, 0, 0, 0, 0);
    emit(label, "HTTP_SEND", ok ? 0 : GetLastError(), 0, 0);
    if (!ok) goto done;
    emit(label, "HTTP_RECEIVE_BEGIN", 0, 0, 0);
    ok = WinHttpReceiveResponse(request, 0);
    emit(label, "HTTP_RECEIVE", ok ? 0 : GetLastError(), 0, 0);
    if (ok) {
        DWORD status = 0, length = sizeof(status);
        ok = WinHttpQueryHeaders(request, 19 | 0x20000000, 0, &status, &length, 0);
        emit(label, "HTTP_STATUS", ok ? 0 : GetLastError(), ok ? status : 0, 0);
    }
done:
    if (request) WinHttpCloseHandle(request);
    if (connection) WinHttpCloseHandle(connection);
    WinHttpCloseHandle(session);
}
void mainCRTStartup(void) {
    unsigned char data[512]; started = GetTickCount64();
    int code = WSAStartup(0x202, data);
    emit("RUNTIME", "WSA_START", code, 0, 0);
    if (code) ExitProcess(1);
    probe("STORE_HTTP", "store.steampowered.com", L"store.steampowered.com", L"/", 80);
    probe("DIRECTORY_HTTPS", "api.steampowered.com", L"api.steampowered.com", L"/ISteamDirectory/GetCMListForConnect/v1/?cellid=0&cmtype=websockets", 443);
    WSACleanup(); emit("RUNTIME", "DONE", 0, 0, 0); ExitProcess(0);
}
