/* Aura's small Windows launch supervisor. No Steam SDK, credentials, emulation or CRT.
 * Reads only ActiveProcess's pid and nonzero ActiveUser in the LIVE Wine registry.
 * A live, matching steam.exe plus that account context is an initial prerequisite.
 * A separate child verifies the official client's IPC before game creation. This
 * still does not claim that the game's SteamAPI_Init or ownership checks succeed.
 * Uses CreateProcessW with a separate application path; game args never enter cmd.
 */
typedef unsigned long DWORD;
typedef unsigned short WORD;
typedef unsigned short WCHAR;
typedef int BOOL;
typedef void *HANDLE;
typedef struct { DWORD low, high; } FILETIME;
typedef struct {
    DWORD cb; WCHAR *reserved, *desktop, *title;
    DWORD x, y, width, height, charsX, charsY, fill, flags;
    WORD show, reservedBytes; void *reservedData;
    HANDLE input, output, error;
} STARTUPINFO;
typedef struct { HANDLE process, thread; DWORD pid, tid; } PROCESSINFO;
typedef struct {
    DWORD size, usage, pid; unsigned long long heap;
    DWORD module, threads, parent; long priority; DWORD flags; WCHAR exe[260];
} PROCESSENTRY;
#define API __declspec(dllimport)
API DWORD __stdcall GetEnvironmentVariableW(const WCHAR *, WCHAR *, DWORD);
API BOOL __stdcall SetEnvironmentVariableW(const WCHAR *, const WCHAR *);
API BOOL __stdcall CreateProcessW(const WCHAR *, WCHAR *, void *, void *, BOOL, DWORD, void *, const WCHAR *, STARTUPINFO *, PROCESSINFO *);
API HANDLE __stdcall OpenProcess(DWORD, BOOL, DWORD);
API BOOL __stdcall GetExitCodeProcess(HANDLE, DWORD *);
API BOOL __stdcall TerminateProcess(HANDLE, DWORD);
API DWORD __stdcall WaitForSingleObject(HANDLE, DWORD);
API DWORD __stdcall GetModuleFileNameW(HANDLE, WCHAR *, DWORD);
API HANDLE __stdcall LoadLibraryExW(const WCHAR *, HANDLE, DWORD);
API void *__stdcall GetProcAddress(HANDLE, const char *);
API BOOL __stdcall QueryFullProcessImageNameW(HANDLE, DWORD, WCHAR *, DWORD *);
API BOOL __stdcall CloseHandle(HANDLE);
API HANDLE __stdcall CreateFileW(const WCHAR *, DWORD, DWORD, void *, DWORD, DWORD, HANDLE);
API BOOL __stdcall WriteFile(HANDLE, const void *, DWORD, DWORD *, void *);
API BOOL __stdcall ReadFile(HANDLE, void *, DWORD, DWORD *, void *);
API BOOL __stdcall FlushFileBuffers(HANDLE);
API void __stdcall Sleep(DWORD);
API void __stdcall ExitProcess(DWORD);
API HANDLE __stdcall CreateToolhelp32Snapshot(DWORD, DWORD);
API BOOL __stdcall Process32FirstW(HANDLE, PROCESSENTRY *);
API BOOL __stdcall Process32NextW(HANDLE, PROCESSENTRY *);
API long __stdcall RegOpenKeyExW(HANDLE, const WCHAR *, DWORD, DWORD, HANDLE *);
API long __stdcall RegQueryValueExW(HANDLE, const WCHAR *, DWORD *, DWORD *, void *, DWORD *);
API long __stdcall RegCloseKey(HANDLE);

static WCHAR client[4096], clientArgs[1024], game[4096], gameArgs[24576], appid[32], statusPath[4096];
static WCHAR eaClient[4096], ubisoftClient[4096];
static WCHAR command[32768], directory[4096], image[4096];
static STARTUPINFO startup;
static PROCESSENTRY processEntry;

static DWORD length(const WCHAR *s) { DWORD n = 0; while (s[n]) ++n; return n; }
static WCHAR lower(WCHAR c) { return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c; }
static BOOL samePath(const WCHAR *a, const WCHAR *b) {
    while (*a && *b && lower(*a) == lower(*b)) { ++a; ++b; }
    return *a == *b;
}
static BOOL env(const WCHAR *key, WCHAR *value, DWORD capacity, BOOL optional) {
    DWORD n = GetEnvironmentVariableW(key, value, capacity);
    return n < capacity && (optional || n > 0);
}
static BOOL pathValid(const WCHAR *p) {
    if (length(p) < 4 || p[1] != ':' || p[2] != '\\') return 0;
    for (; *p; ++p) if (*p < 32 || *p == '"') return 0;
    return 1;
}
static void writeStatus(const WCHAR *path, const char *value) {
    if (!path[0]) return;
    DWORD n = 0, written;
    while (value[n]) ++n;
    HANDLE out = CreateFileW(path, 0x40000000, 7, 0, 2, 0x80, 0);
    if (out == (HANDLE)-1) return;
    WriteFile(out, value, n, &written, 0);
    FlushFileBuffers(out);
    CloseHandle(out);
}
static void state(const char *value) { writeStatus(statusPath, value); }
#include "steam_proxy.h"
static BOOL startProcess(const WCHAR *exe, const WCHAR *args, PROCESSINFO *retained) {
    DWORD n = length(exe), a = length(args);
    if (n + a + 5 > 32768) return 0;
    DWORD i, end = 0;
    command[0] = '"';
    for (i = 0; i < n; ++i) {
        command[i + 1] = exe[i]; directory[i] = exe[i];
        if (exe[i] == '\\') end = i;
    }
    directory[end == 2 ? 3 : end] = 0; /* C:\ stays absolute, never drive-relative C: */
    command[n + 1] = '"'; command[n + 2] = ' ';
    for (i = 0; i <= a; ++i) command[n + 3 + i] = args[i];
    PROCESSINFO process;
    startup.cb = sizeof(startup);
    if (!CreateProcessW(exe, command, 0, 0, 0, 0, 0, directory, &startup, &process)) return 0;
    CloseHandle(process.thread);
    if (retained) *retained = process;
    else CloseHandle(process.process);
    return 1;
}
static BOOL start(const WCHAR *exe, const WCHAR *args) { return startProcess(exe, args, 0); }
/* Only a live executable at the configured installation path is reusable. A same-name
 * process in a game's directory cannot stand in for the required installed client. */
static BOOL alreadyRunning(const WCHAR *exe) {
    HANDLE snapshot = CreateToolhelp32Snapshot(2, 0);
    if (snapshot == (HANDLE)-1) return 0;
    const WCHAR *name = exe;
    for (DWORD i = 0; exe[i]; ++i) if (exe[i] == '\\') name = exe + i + 1;
    processEntry.size = sizeof(processEntry);
    BOOL more = Process32FirstW(snapshot, &processEntry), found = 0;
    while (more && !found) {
        if (samePath(processEntry.exe, name)) {
            HANDLE process = OpenProcess(0x1000, 0, processEntry.pid);
            DWORD size = 4096, code = 0;
            found = process && GetExitCodeProcess(process, &code) && code == 259 &&
                QueryFullProcessImageNameW(process, 0, image, &size) && samePath(image, exe);
            if (process) CloseHandle(process);
        }
        more = Process32NextW(snapshot, &processEntry);
    }
    CloseHandle(snapshot);
    return found;
}
static BOOL backgroundClient(const WCHAR *exe, const WCHAR *args) {
    return !exe[0] || alreadyRunning(exe) || start(exe, args);
}
static BOOL dword(HANDLE key, const WCHAR *name, DWORD *value) {
    DWORD size = sizeof(DWORD), type = 0;
    return RegQueryValueExW(key, name, 0, &type, value, &size) == 0 && type == 4 && size == sizeof(DWORD);
}
static const char *gate64 = "NOT_CHECKED", *gate32 = "NOT_CHECKED";
static DWORD liveClient(DWORD view) {
    const char **reason = view == 0x100 ? &gate64 : &gate32;
    *reason = "REGISTRY_UNAVAILABLE";
    HANDLE key;
    if (RegOpenKeyExW((HANDLE)(long long)(int)0x80000001, L"Software\\Valve\\Steam\\ActiveProcess", 0, 1 | view, &key)) return 0;
    DWORD pid = 0, user = 0;
    BOOL valid = 0;
    if (!dword(key, L"pid", &pid) || !pid) *reason = "PID_UNAVAILABLE";
    else if (!dword(key, L"ActiveUser", &user)) *reason = "USER_UNAVAILABLE";
    else if (!user) *reason = "NO_ACTIVE_USER";
    else valid = 1;
    RegCloseKey(key);
    if (!valid) return 0;
    *reason = "PROCESS_UNAVAILABLE";
    HANDLE process = OpenProcess(0x1000, 0, pid);
    if (!process) return 0;
    DWORD code = 0, size = 4096;
    valid = 0;
    if (!GetExitCodeProcess(process, &code)) *reason = "PROCESS_UNAVAILABLE";
    else if (code != 259) *reason = "PROCESS_EXITED";
    else if (!QueryFullProcessImageNameW(process, 0, image, &size)) *reason = "IMAGE_UNAVAILABLE";
    else if (!samePath(image, client)) *reason = "PATH_MISMATCH";
    else { valid = 1; *reason = "CONTEXT_AVAILABLE"; }
    CloseHandle(process);
    return valid ? pid : 0;
}

static BOOL clientStillRunning(void) {
    HANDLE snapshot = CreateToolhelp32Snapshot(2, 0);
    if (snapshot == (HANDLE)-1) return 1; /* unavailable is not evidence of exit */
    processEntry.size = sizeof(processEntry);
    BOOL more = Process32FirstW(snapshot, &processEntry), found = 0;
    if (!more) found = 1; /* an unavailable/empty reply is inconclusive */
    while (more && !found) {
        if (samePath(processEntry.exe, L"steam.exe")) {
            HANDLE process = OpenProcess(0x1000, 0, processEntry.pid);
            DWORD size = 4096;
            found = !process || !QueryFullProcessImageNameW(process, 0, image, &size) || samePath(image, client);
            if (process) CloseHandle(process);
        }
        more = Process32NextW(snapshot, &processEntry);
    }
    CloseHandle(snapshot);
    return found;
}

#include "steam_ready.h"

/* Report why the login gate is waiting, without exposing registry values or paths.
 * Interned fixed tokens allow change-only writes; readiness remains the IPC probe's job. */
static void reportClientGate(void) {
    static const char *previous64, *previous32;
    static WCHAR path[4096];
    if (previous64 == gate64 && previous32 == gate32) return;
    if (!siblingPath(statusPath, L"client-gate-status.txt", path)) return;
    const char *parts[] = { "STEAM_CLIENT_GATE_V1 view64=", gate64, "; view32=", gate32,
        "; authentication=UNCONFIRMED" };
    char text[256];
    DWORD n = 0;
    for (DWORD i = 0; i < sizeof(parts) / sizeof(parts[0]); ++i)
        for (const char *p = parts[i]; *p && n < sizeof(text) - 1; ++p) text[n++] = *p;
    text[n] = 0;
    writeStatus(path, text);
    previous64 = gate64; previous32 = gate32;
}

/* The host requests this only after updating has finished, or explicitly from
 * the session menu. Steam forwards this URI to its existing instance; never kill
 * or restart the updater, disable CEF checks, or skip the real login prerequisite.
 * Fixed command records and a per-session limit prevent repeated spawn loops. */
static void pollPresentation(void) {
    static WCHAR path[4096], report[4096];
    static unsigned lastRequest;
    char request[8];
    DWORD size = 0;
    if (!siblingPath(statusPath, L"client-command.txt", path) ||
        !siblingPath(statusPath, L"client-presentation.txt", report)) return;
    HANDLE in = CreateFileW(path, 0x80000000, 7, 0, 3, 0x80, 0);
    if (in == (HANDLE)-1) return;
    BOOL read = ReadFile(in, request, sizeof(request), &size, 0);
    CloseHandle(in);
    if (!read || size != 6 || request[0] != 'S' || request[1] != 'H' || request[2] != 'O' ||
        request[3] != 'W' || request[4] < '1' || request[4] > '3' || request[5] != '\n') return;
    unsigned next = request[4] - '0';
    if (next <= lastRequest) return;
    lastRequest = next;
    if (!alreadyRunning(client)) {
        writeStatus(report, "NO_LIVE_CLIENT");
        return;
    }
    writeStatus(report, start(client, L"steam://open/main") ? "FOREGROUND_REQUESTED" : "FOREGROUND_FAILED");
}

void mainCRTStartup(void) {
    if (!env(L"AURA_STEAM_STATUS", statusPath, 4096, 0) || !pathValid(statusPath)) ExitProcess(1);
    if (!env(L"AURA_STEAM_CLIENT", client, 4096, 1) || (client[0] && !pathValid(client)) ||
        !env(L"AURA_STEAM_APPID", appid, 32, !client[0])) {
        state("INVALID_CONFIGURATION"); ExitProcess(1);
    }
    for (DWORD i = 0; appid[i]; ++i) if (appid[i] < '0' || appid[i] > '9') {
        state("INVALID_CONFIGURATION"); ExitProcess(1);
    }
    WCHAR probeMode[2] = {0, 0};
    if (!env(L"AURA_STEAM_PROBE", probeMode, 2, 1)) { state("INVALID_CONFIGURATION"); ExitProcess(1); }
    if (probeMode[0] == '1') {
        if (!client[0]) { state("INVALID_CONFIGURATION"); ExitProcess(1); }
        probeClientIpc(); ExitProcess(1);
    }
    if (!env(L"AURA_STEAM_ARGS", clientArgs, 1024, !client[0]) ||
        !env(L"AURA_STEAM_GAME", game, 4096, 0) || !pathValid(game) ||
        !env(L"AURA_STEAM_GAME_ARGS", gameArgs, 24576, 1) ||
        !env(L"AURA_CLIENT_EA", eaClient, 4096, 1) || (eaClient[0] && !pathValid(eaClient)) ||
        !env(L"AURA_CLIENT_UBISOFT", ubisoftClient, 4096, 1) || (ubisoftClient[0] && !pathValid(ubisoftClient)) ||
        (!client[0] && !eaClient[0] && !ubisoftClient[0])) {
        state("INVALID_CONFIGURATION"); ExitProcess(1);
    }
    // Do not give Steam itself the game's identity or Aura's internal handoff variables.
    const WCHAR *keys[] = { L"SteamAppId", L"SteamGameId", L"AURA_STEAM_STATUS", L"AURA_STEAM_CLIENT",
        L"AURA_STEAM_ARGS", L"AURA_STEAM_GAME", L"AURA_STEAM_GAME_ARGS", L"AURA_STEAM_APPID", L"AURA_STEAM_PROBE",
        L"AURA_CLIENT_EA", L"AURA_CLIENT_UBISOFT" };
    for (DWORD i = 0; i < sizeof(keys) / sizeof(keys[0]); ++i) SetEnvironmentVariableW(keys[i], 0);
    if (client[0]) {
        state("CONFIGURING_PROXY");
        if (!prepareLiveProxy()) { state("PROXY_SETUP_FAILED"); ExitProcess(1); }
        state("STARTING_CLIENT");
        if (!start(client, clientArgs)) { state("CLIENT_START_FAILED"); ExitProcess(1); }
        state("WAITING_FOR_CLIENT_LOGIN");
        DWORD previous = 0, absent = 0;
        // No fake success or timeout-based launch. Updating and Steam Guard can take time;
        // Aura's normal session exit/pause controls own cancellation and suspension.
        for (;;) {
            Sleep(2000);
            gate64 = gate32 = "NOT_CHECKED";
            DWORD pid = liveClient(0x100);
            if (!pid) pid = liveClient(0x200);
            reportClientGate();
            if (pid && pid == previous) {
                int ready = waitForClientIpc(pid);
                if (ready > 0) break;
                if (ready < 0) ExitProcess(1);
                previous = 0; /* A client restart invalidates the entire probe. */
                state("WAITING_FOR_CLIENT_LOGIN");
                continue;
            }
            previous = pid;
            // Read requests only while waiting for authentication, never during
            // the official IPC probe or after game creation.
            pollPresentation();
            absent = clientStillRunning() ? 0 : absent + 1;
            // Let the updater replace/restart Steam. A cancelled updater with no client
            // for ten consecutive snapshots must not leave a permanent black session.
            if (absent >= 10) { state("CLIENT_EXITED"); ExitProcess(1); }
        }
        state("CLIENT_SESSION_AVAILABLE");
    }
    if (eaClient[0] || ubisoftClient[0]) {
        state("STARTING_GAME_CLIENTS");
        // These are runtime switches, not installer /S flags. Do not minimize/hide
        // the processes with Win32: they must be free to show login/2FA/consent UI.
        if (!backgroundClient(eaClient, L"-silent") || !backgroundClient(ubisoftClient, L"-uplay_silent")) {
            state("GAME_CLIENT_START_FAILED"); ExitProcess(1);
        }
    }
    if (appid[0] && (!SetEnvironmentVariableW(L"SteamAppId", appid) || !SetEnvironmentVariableW(L"SteamGameId", appid))) {
        state("GAME_START_FAILED"); ExitProcess(1);
    }
    state("GAME_STARTING");
    if (!start(game, gameArgs)) { state("GAME_START_FAILED"); ExitProcess(1); }
    state("GAME_STARTED");
    // The root Wine/WinHandler lifetime can follow this launcher. Keep it alive;
    // Aura observes the actual game/child processes and owns session cleanup.
    for (;;) Sleep(60000);
}
