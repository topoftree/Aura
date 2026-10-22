/*
 * Opt-in x64 GetTickCount entry for MgsGroundZeroes.exe on ARM64EC Wine.
 * Copyright 2026 Aura contributors
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Included by ntdll/loader.c after find_name_in_exports(). The loader lock
 * protects the cache. This does not replace the clock implementation: the
 * private entry always forwards to the original export. Some old hook
 * builders cannot relocate the backwards E9 in Wine's compact EC entries
 * and overwrite the next export. Give this process an isolated, forward E9.
 */

static BOOL aura_mgs_ascii_equal( const WCHAR *text, unsigned int length, const char *expected )
{
    unsigned int i;
    for (i = 0; i < length && expected[i]; ++i)
    {
        WCHAR ch = text[i];
        char match = expected[i];
        if (ch >= 'A' && ch <= 'Z') ch += 'a' - 'A';
        if (match >= 'A' && match <= 'Z') match += 'a' - 'A';
        if (ch != match) return FALSE;
    }
    return i == length && !expected[i];
}

static BOOL aura_mgs_tick_enabled(void)
{
    RTL_USER_PROCESS_PARAMETERS *params = NtCurrentTeb()->Peb->ProcessParameters;
    UNICODE_STRING name = RTL_CONSTANT_STRING( L"AURA_MGS_TICK_THUNK" ), value;
    WCHAR flag[2];
    unsigned int start = 0, i, length;

    if (!params || !params->ImagePathName.Buffer || (params->ImagePathName.Length & 1)) return FALSE;
    length = params->ImagePathName.Length / sizeof(WCHAR);
    for (i = 0; i < length; ++i)
        if (params->ImagePathName.Buffer[i] == '\\' || params->ImagePathName.Buffer[i] == '/') start = i + 1;
    if (!aura_mgs_ascii_equal( params->ImagePathName.Buffer + start, length - start,
                              "MgsGroundZeroes.exe" )) return FALSE;
    value.Buffer = flag;
    value.Length = 0;
    value.MaximumLength = sizeof(flag);
    return !RtlQueryEnvironmentVariable_U( NULL, &name, &value ) &&
           value.Length == sizeof(WCHAR) && flag[0] == '1';
}

/* Returns the same pointer for static imports and GetProcAddress. Never retry
 * a failed allocation after publishing the original pointer to an importer. */
static FARPROC aura_mgs_tick_export( HMODULE module, const IMAGE_EXPORT_DIRECTORY *exports,
                                    DWORD ordinal, FARPROC proc )
{
    static BOOL attempted;
    static FARPROC original, replacement;
    static const BYTE prefix[] = {0x48,0x8b,0xc4,0x48,0x89,0x58,0x20,0x55,0x5d,0xe9};
    WINE_MODREF *wm = get_modref( module );
    BYTE *entry = (BYTE *)proc, *page = NULL;
    SIZE_T size = 4096, release_size = 0;
    DWORD old_protect;
    LONG displacement;
    ULONG_PTR offset;

    if (!wm || !(wm->ldr.Flags & LDR_WINE_INTERNAL) ||
        !aura_mgs_ascii_equal( wm->ldr.BaseDllName.Buffer, wm->ldr.BaseDllName.Length / sizeof(WCHAR),
                              "kernel32.dll" ) ||
        (DWORD)find_name_in_exports( module, exports, "GetTickCount" ) != ordinal) return proc;
    if (attempted) return proc == original && replacement ? replacement : proc;
    attempted = TRUE;
    original = proc;
    if (!aura_mgs_tick_enabled()) return proc;

    /* Fail closed for a different runtime layout or a custom system DLL. */
    offset = (ULONG_PTR)entry - (ULONG_PTR)module;
    if (wm->ldr.SizeOfImage < 14 || offset > wm->ldr.SizeOfImage - 14 ||
        memcmp( entry, prefix, sizeof(prefix) )) return proc;
    memcpy( &displacement, entry + 10, sizeof(displacement) );
    if ((LONGLONG)offset + 14 + displacement < 0 ||
        (LONGLONG)offset + 14 + displacement >= wm->ldr.SizeOfImage) return proc;

    if (NtAllocateVirtualMemory( GetCurrentProcess(), (void **)&page, 0, &size,
                                MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE )) return proc;
    memset( page, 0xcc, size );
    /* E9 +59 -> entry+64; fourteen writable entry bytes cannot touch another
     * function. FF25 +0 at +64 forwards indirectly to the untouched export. */
    page[0] = 0xe9;
    displacement = 64 - 5;
    memcpy( page + 1, &displacement, sizeof(displacement) );
    page[64] = 0xff;
    page[65] = 0x25;
    memset( page + 66, 0, 4 );
    memcpy( page + 70, &proc, sizeof(proc) );
    if (NtProtectVirtualMemory( GetCurrentProcess(), (void **)&page, &size,
                               PAGE_EXECUTE_READ, &old_protect ) ||
        NtFlushInstructionCache( GetCurrentProcess(), page, size ))
    {
        NtFreeVirtualMemory( GetCurrentProcess(), (void **)&page, &release_size, MEM_RELEASE );
        return proc;
    }
    replacement = (FARPROC)page;
    TRACE_(loaddll)("AURA_MGS_TICK_THUNK_V1 state=ACTIVE; entry=%p; original=%p; clock=FORWARDED\n",
                    page, proc );
    return replacement;
}
