/* Host-only resource discovery regression. Model Wine's server/unicode.c
 * get_nls_dir(): resolve /proc/self/exe, then locate ../share/wine/nls.
 * Reference: The412Banner/proton-wine@256288853b206373cbf65b775143d33bb11035dd.
 * This exercises the APK hashed-file/symlink layout, not Windows execution.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    /* Wine handles --version before initializing its NLS resources. */
    if (argc == 2 && !strcmp(argv[1], "--version")) {
        puts("Wine test server");
        return 0;
    }
    char *self = realpath("/proc/self/exe", NULL);
    if (!self) return 2;
    char *slash = strrchr(self, '/');
    if (!slash) { free(self); return 2; }
    *slash = 0;
    char *nls = NULL;
    if (asprintf(&nls, "%s/../share/wine/nls/l_intl.nls", self) < 0) {
        free(self);
        return 2;
    }
    free(self);
    FILE *file = fopen(nls, "rb");
    free(nls);
    if (!file) {
        fputs("wineserver: failed to load l_intl.nls\n", stderr);
        return 1;
    }
    int value = fgetc(file);
    fclose(file);
    return value == EOF ? 2 : 0;
}
