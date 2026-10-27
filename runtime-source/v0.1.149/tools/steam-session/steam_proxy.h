/* Configure the LIVE registry after wineboot, before creating Steam. Offline
 * user.reg readback does not prove what the updater's WinHTTP actually sees.
 * Only WPAD is disabled. Existing manual proxy/PAC payloads remain byte-exact.
 */
API long __stdcall RegCreateKeyExW(HANDLE, const WCHAR *, DWORD, WCHAR *, DWORD, DWORD, void *, HANDLE *, DWORD *);
API long __stdcall RegSetValueExW(HANDLE, const WCHAR *, DWORD, DWORD, const void *, DWORD);
API HANDLE __stdcall GlobalFree(HANDLE);
typedef struct { BOOL autoDetect; WCHAR *pac, *proxy, *bypass; } IE_PROXY_CONFIG;
API BOOL __stdcall WinHttpGetIEProxyConfigForCurrentUser(IE_PROXY_CONFIG *);

static const WCHAR internetKey[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
static const WCHAR connectionsKey[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Connections";
static const WCHAR connectionValue[] = L"DefaultConnectionSettings";
static unsigned char proxyBlob[16384], proxyReadback[16384];
static WCHAR proxyStatusPath[4096];
static char proxyReport[256];

static DWORD proxyDword(const unsigned char *p) {
    return p[0] | ((DWORD)p[1] << 8) | ((DWORD)p[2] << 16) | ((DWORD)p[3] << 24);
}
static BOOL proxyValid(const unsigned char *data, DWORD size) {
    if (size < 24 || size > sizeof(proxyBlob) || proxyDword(data) != 0x46 || proxyDword(data + 8) > 15) return 0;
    DWORD offset = 12;
    for (DWORD i = 0; i < 3; ++i) {
        if (offset > size - 4) return 0;
        DWORD n = proxyDword(data + offset); offset += 4;
        if (n > size - offset) return 0;
        offset += n;
    }
    return 1;
}
static BOOL proxyEqual(const unsigned char *a, const unsigned char *b, DWORD n) {
    for (DWORD i = 0; i < n; ++i) if (a[i] != b[i]) return 0;
    return 1;
}
/* A missing connection blob can mean a new Wine user, or an explicit legacy
 * configuration. Only the first case may acquire a new direct default. */
static BOOL proxyCanDefault(DWORD view) {
    HANDLE key;
    long result = RegOpenKeyExW((HANDLE)(long long)(int)0x80000001, internetKey, 0, 1 | view, &key);
    if (result == 2) return 1;
    if (result) return 0;
    DWORD enabled = 0, type = 0, size = 4;
    result = RegQueryValueExW(key, L"ProxyEnable", 0, &type, &enabled, &size);
    BOOL disabled = (result == 2 || (!result && type == 4 && size == 4 && enabled == 0));
    BOOL absentEnable = result == 2;
    const WCHAR *values[] = { L"AutoConfigURL", L"ProxyServer" };
    for (DWORD i = 0; i < 2 && disabled; ++i) {
        if (i == 1 && !absentEnable) continue; /* explicitly disabled stale proxy */
        WCHAR empty[2]; size = sizeof(empty); type = 0;
        result = RegQueryValueExW(key, values[i], 0, &type, empty, &size);
        disabled = result == 2 || (!result && type == 1 && size == 2 && empty[0] == 0);
    }
    RegCloseKey(key);
    return disabled;
}
static BOOL proxyBackup(DWORD view, BOOL present, DWORD size) {
    const WCHAR *path = view == 0x100 ? L"Software\\Aura\\SteamProxyBackupV2\\View64" : L"Software\\Aura\\SteamProxyBackupV2\\View32";
    HANDLE key; DWORD disposition;
    /* Keep the backup in the unredirected view, with separate original states. */
    if (RegCreateKeyExW((HANDLE)(long long)(int)0x80000001, path, 0, 0, 0, 3 | 0x100, 0, &key, &disposition)) return 0;
    DWORD original, type = 0, bytes = 4;
    long result = RegQueryValueExW(key, L"OriginalPresent", 0, &type, &original, &bytes);
    BOOL ok = !result && type == 4 && bytes == 4 && original <= 1;
    if (result == 2) {
        original = present;
        ok = !present || !RegSetValueExW(key, connectionValue, 0, 3, proxyBlob, size);
        if (ok) ok = !RegSetValueExW(key, L"OriginalPresent", 0, 4, &original, 4);
    }
    RegCloseKey(key);
    return ok;
}
static const char *proxyView(DWORD view, BOOL *ok) {
    HANDLE key; DWORD size = sizeof(proxyBlob), type = 0;
    *ok = 0;
    long result = RegOpenKeyExW((HANDLE)(long long)(int)0x80000001, connectionsKey, 0, 3 | view, &key);
    if (result == 2) {
        if (!proxyCanDefault(view)) return "EXPLICIT_OR_UNREADABLE_SETTINGS";
        DWORD disposition;
        if (RegCreateKeyExW((HANDLE)(long long)(int)0x80000001, connectionsKey, 0, 0, 0, 3 | view, 0, &key, &disposition)) return "OPEN_FAILED";
    } else if (result) return "OPEN_FAILED";
    result = RegQueryValueExW(key, connectionValue, 0, &type, proxyBlob, &size);
    BOOL missing = result == 2;
    if ((result && !missing) || (!missing && (type != 3 || !proxyValid(proxyBlob, size)))) {
        RegCloseKey(key); return "INVALID_OR_UNREADABLE_BLOB";
    }
    if (missing && !proxyCanDefault(view)) { RegCloseKey(key); return "EXPLICIT_OR_UNREADABLE_SETTINGS"; }
    BOOL automatic = !missing && (proxyBlob[8] & 8);
    if (missing || automatic) {
        if (!proxyBackup(view, !missing, size)) { RegCloseKey(key); return "BACKUP_FAILED"; }
        if (missing) {
            size = 24;
            for (DWORD i = 0; i < size; ++i) proxyBlob[i] = 0;
            proxyBlob[0] = 0x46; proxyBlob[8] = 1;
        } else {
            proxyBlob[8] &= ~8;
            if (!proxyBlob[8]) proxyBlob[8] = 1;
        }
        if (RegSetValueExW(key, connectionValue, 0, 3, proxyBlob, size)) {
            RegCloseKey(key); return "WRITE_FAILED";
        }
    }
    DWORD actual = sizeof(proxyReadback);
    result = RegQueryValueExW(key, connectionValue, 0, &type, proxyReadback, &actual);
    RegCloseKey(key);
    if (result || type != 3 || actual != size || !proxyEqual(proxyBlob, proxyReadback, size)) return "READBACK_FAILED";
    *ok = 1;
    if (missing) return "MISSING_TO_DIRECT";
    if (automatic) return "AUTO_DISABLED";
    return "ALREADY_OFF";
}
static void proxyAppend(const char *text) {
    DWORD n = 0; while (proxyReport[n]) ++n;
    for (DWORD i = 0; text[i] && n + 1 < sizeof(proxyReport); ++i) proxyReport[n++] = text[i];
    proxyReport[n] = 0;
}
static BOOL prepareLiveProxy(void) {
    /* statusPath was validated by the host and main entry. Use its private folder. */
    DWORD n = length(statusPath), slash = 0;
    for (DWORD i = 0; i < n; ++i) { proxyStatusPath[i] = statusPath[i]; if (statusPath[i] == '\\') slash = i + 1; }
    const WCHAR suffix[] = L"proxy-status.txt";
    if (!slash || slash + sizeof(suffix) / sizeof(WCHAR) > 4096) return 0;
    for (DWORD i = 0; i < sizeof(suffix) / sizeof(WCHAR); ++i) proxyStatusPath[slash + i] = suffix[i];
    BOOL ok64, ok32;
    const char *view64 = proxyView(0x100, &ok64);
    const char *view32 = proxyView(0x200, &ok32);
    /* Invoke the real Wine API as well as checking the two live registry views.
     * This x64 call verifies x64 WinHTTP only; view32 is registry readback. */
    IE_PROXY_CONFIG config = {0};
    BOOL queried = WinHttpGetIEProxyConfigForCurrentUser(&config);
    BOOL verified = ok64 && ok32 && queried && !config.autoDetect;
    const char *api = !queried ? "FAILED" : config.autoDetect ? "AUTODETECT_ON" : "AUTODETECT_OFF";
    if (config.pac) GlobalFree(config.pac);
    if (config.proxy) GlobalFree(config.proxy);
    if (config.bypass) GlobalFree(config.bypass);
    proxyAppend("STEAM_PROXY_LIVE_V2 view64="); proxyAppend(view64);
    proxyAppend("; view32="); proxyAppend(view32);
    proxyAppend("; winhttp64="); proxyAppend(api);
    proxyAppend(verified ? "; result=VERIFIED" : "; result=FAILED");
    writeStatus(proxyStatusPath, proxyReport);
    return verified;
}
