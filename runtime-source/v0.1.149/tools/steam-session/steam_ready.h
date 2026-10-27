/* Official Steam IPC prerequisite, isolated from the launch supervisor.
 * ABI: ISteamClient SteamClient020, first five public methods. No SDK binary,
 * emulated account, license result, callbacks or game DLL are supplied by Aura.
 * The selected Steam install provides both the module and the implementation.
 */
#define IPC_READY_EXIT 0x41555241
#define IPC_FAILED_EXIT 0x41555242
typedef struct {
    int (*createPipe)(void *);
    unsigned char (*releasePipe)(void *, int);
    int (*connectUser)(void *, int);
    void *unusedCreateLocalUser;
    void (*releaseUser)(void *, int, int);
} ClientMethods;
typedef struct { const ClientMethods *methods; } OfficialClient;
static WCHAR ipcReportPath[4096], clientLibrary[4096], probeExecutable[4096];

static BOOL siblingPath(const WCHAR *path, const WCHAR *name, WCHAR *out) {
    DWORD end = 0, n = length(path), count = length(name);
    for (DWORD i = 0; i < n; ++i) if (path[i] == '\\') end = i + 1;
    if (!end || end + count >= 4096) return 0;
    for (DWORD i = 0; i < end; ++i) out[i] = path[i];
    for (DWORD i = 0; i <= count; ++i) out[end + i] = name[i];
    return 1;
}
static void ipcReport(const char *phase) {
    if (siblingPath(statusPath, L"client-ipc-status.txt", ipcReportPath)) writeStatus(ipcReportPath, phase);
}
static void probeClientIpc(void) {
    /* A fresh process owns the SDK module/threads. Normal handle release followed
     * by process exit avoids keeping another Steam client DLL in the game host. */
    const WCHAR *keys[] = { L"AURA_STEAM_PROBE", L"AURA_STEAM_CLIENT", L"AURA_STEAM_APPID", L"AURA_STEAM_STATUS" };
    for (DWORD i = 0; i < sizeof(keys) / sizeof(keys[0]); ++i) SetEnvironmentVariableW(keys[i], 0);
    if (!SetEnvironmentVariableW(L"SteamAppId", appid) || !SetEnvironmentVariableW(L"SteamGameId", appid) ||
        !siblingPath(client, L"steamclient64.dll", clientLibrary)) {
        ipcReport("STEAM_CLIENT_IPC_V1 phase=CONFIGURATION_FAILED"); ExitProcess(IPC_FAILED_EXIT);
    }
    ipcReport("STEAM_CLIENT_IPC_V1 phase=LOADING_LIBRARY");
    /* Absolute installed path, with its own directory used for dependencies. */
    HANDLE module = 0;
    DWORD attempts = 0;
    for (; attempts < 60 && !module; ++attempts) {
        module = LoadLibraryExW(clientLibrary, 0, 8 /* LOAD_WITH_ALTERED_SEARCH_PATH */);
        if (!module) Sleep(2000);
    }
    if (!module) { ipcReport("STEAM_CLIENT_IPC_V1 phase=LIBRARY_LOAD_FAILED"); ExitProcess(IPC_FAILED_EXIT); }
    OfficialClient *(*factory)(const char *, int *) = (void *)GetProcAddress(module, "CreateInterface");
    int result = 0;
    OfficialClient *api = factory ? factory("SteamClient020", &result) : 0;
    if (!api || !api->methods || !api->methods->createPipe || !api->methods->connectUser ||
        !api->methods->releaseUser || !api->methods->releasePipe) {
        ipcReport("STEAM_CLIENT_IPC_V1 phase=INTERFACE_UNAVAILABLE"); ExitProcess(IPC_FAILED_EXIT);
    }
    for (; attempts < 60; ++attempts) {
        ipcReport("STEAM_CLIENT_IPC_V1 phase=WAITING_FOR_PIPE");
        int pipe = api->methods->createPipe(api);
        if (pipe) {
            ipcReport("STEAM_CLIENT_IPC_V1 phase=WAITING_FOR_USER");
            int user = api->methods->connectUser(api, pipe);
            if (user) api->methods->releaseUser(api, pipe, user);
            unsigned char released = api->methods->releasePipe(api, pipe);
            if (!released) { ipcReport("STEAM_CLIENT_IPC_V1 phase=RELEASE_FAILED"); ExitProcess(IPC_FAILED_EXIT); }
            if (user) {
                ipcReport("STEAM_CLIENT_IPC_V1 phase=READY");
                ExitProcess(IPC_READY_EXIT);
            }
        }
        Sleep(2000);
    }
    ipcReport("STEAM_CLIENT_IPC_V1 phase=TIMEOUT");
    ExitProcess(IPC_FAILED_EXIT);
}

static void stopProbe(HANDLE process) {
    TerminateProcess(process, IPC_FAILED_EXIT);
    WaitForSingleObject(process, 5000);
    CloseHandle(process);
}

/* 1 = IPC verified, 0 = Steam changed/restarted, -1 = explicit failure.
 * Poll counts advance only as the guest runs; paused sessions do not spend
 * their deadline. Never substitute a delay for successful official IPC. */
static int waitForClientIpc(DWORD expectedPid) {
    DWORD n = GetModuleFileNameW(0, probeExecutable, 4096);
    if (!n || n >= 4096 || !pathValid(probeExecutable)) { state("CLIENT_PROBE_START_FAILED"); return -1; }
    state("WAITING_FOR_CLIENT_IPC");
    ipcReport("STEAM_CLIENT_IPC_V1 phase=STARTING_PROBE");
    BOOL configured = SetEnvironmentVariableW(L"AURA_STEAM_PROBE", L"1") &&
        SetEnvironmentVariableW(L"AURA_STEAM_CLIENT", client) &&
        SetEnvironmentVariableW(L"AURA_STEAM_STATUS", statusPath) &&
        SetEnvironmentVariableW(L"AURA_STEAM_APPID", appid);
    PROCESSINFO probe;
    BOOL started = configured && startProcess(probeExecutable, L"", &probe);
    const WCHAR *keys[] = { L"AURA_STEAM_PROBE", L"AURA_STEAM_CLIENT", L"AURA_STEAM_STATUS", L"AURA_STEAM_APPID" };
    for (DWORD i = 0; i < sizeof(keys) / sizeof(keys[0]); ++i) SetEnvironmentVariableW(keys[i], 0);
    if (!started) { state("CLIENT_PROBE_START_FAILED"); return -1; }
    for (DWORD i = 0; i < 60; ++i) {
        Sleep(2000);
        DWORD pid = liveClient(0x100);
        if (!pid) pid = liveClient(0x200);
        if (pid != expectedPid) {
            stopProbe(probe.process);
            ipcReport("STEAM_CLIENT_IPC_V1 phase=CLIENT_CHANGED");
            return 0;
        }
        DWORD code = 259;
        if (!GetExitCodeProcess(probe.process, &code)) {
            stopProbe(probe.process); state("CLIENT_IPC_FAILED"); return -1;
        }
        if (code != 259) {
            CloseHandle(probe.process);
            if (code == IPC_READY_EXIT) { state("CLIENT_IPC_READY"); return 1; }
            state("CLIENT_IPC_FAILED"); return -1;
        }
    }
    stopProbe(probe.process);
    ipcReport("STEAM_CLIENT_IPC_V1 phase=TIMEOUT");
    state("CLIENT_IPC_TIMEOUT"); return -1;
}
