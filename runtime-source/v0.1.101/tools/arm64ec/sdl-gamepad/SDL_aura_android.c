/* Headless Android platform for Aura's Wine-only SDL library (zlib license).
 * Wine processes have no JavaVM or SDLActivity. Joystick events use UDP,
 * filesystem I/O uses libc, and Activity-only APIs report unsupported.
 */
#include "../../SDL_internal.h"
#include "SDL_system.h"
#include "SDL_error.h"
#include <android/api-level.h>

void *SDL_AndroidGetJNIEnv(void) { return NULL; }
void *SDL_AndroidGetActivity(void) { return NULL; }
int SDL_GetAndroidSDKVersion(void) { return android_get_device_api_level(); }
SDL_bool SDL_IsAndroidTV(void) { return SDL_FALSE; }
SDL_bool SDL_IsAndroidTablet(void) { return SDL_FALSE; }
SDL_bool SDL_IsChromebook(void) { return SDL_FALSE; }
SDL_bool SDL_IsDeXMode(void) { return SDL_FALSE; }
void SDL_AndroidBackButton(void) { SDL_Unsupported(); }
const char *SDL_AndroidGetInternalStoragePath(void) { SDL_Unsupported(); return NULL; }
const char *SDL_AndroidGetExternalStoragePath(void) { SDL_Unsupported(); return NULL; }
int SDL_AndroidGetExternalStorageState(void) { return 0; }
SDL_bool SDL_AndroidRequestPermission(const char *permission) { SDL_Unsupported(); return SDL_FALSE; }
int SDL_AndroidShowToast(const char *message, int duration, int gravity, int x, int y) { return SDL_Unsupported(); }
int SDL_AndroidSendMessage(Uint32 command, int param) { return SDL_Unsupported(); }
int Android_JNI_SetupThread(void) { return 1; }
SDL_bool Android_JNI_ShouldMinimizeOnFocusLoss(void) { return SDL_FALSE; }
void Android_JNI_GetManifestEnvironmentVariables(void) { }
