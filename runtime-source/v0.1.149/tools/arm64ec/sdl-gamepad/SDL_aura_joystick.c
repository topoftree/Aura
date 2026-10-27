/* Aura Wine input backend for SDL 2.32.10 (zlib license, see LICENSE).
 * Runs in Wine's native Bionic process, without an Android Activity or JNI.
 * Uses the existing Winlator loopback UDP protocol; SDL/Wine produce HID,
 * DirectInput and XInput devices. No Windows ABI structures are duplicated.
 */
#include "../../SDL_internal.h"
#include "SDL_joystick.h"
#include "SDL_timer.h"
#include "../SDL_sysjoystick.h"
#include "../SDL_joystick_c.h"
#include <arpa/inet.h>
#include <errno.h>
#include <sys/socket.h>
#include <unistd.h>

#define SLOT_COUNT 4
#define HOST_PORT 7947
#define DISCOVERY_MS 250
#define TIMEOUT_MS 3000

typedef struct {
    SDL_bool present;
    Uint8 buttons, axis_mode, vibration;
    Uint16 vid, pid;
    char name[49];
    SDL_JoystickID instance;
    SDL_Joystick *open;
    Uint16 pressed;
    Uint8 hat;
    Sint16 axes[6];
} AuraSlot;

struct joystick_hwdata { int slot; SDL_JoystickID instance; };
static AuraSlot slots[SLOT_COUNT];
static int channel = -1;
static Uint32 last_query, last_reply;

static Uint16 read16(const Uint8 *p) { return (Uint16)(p[0] | (p[1] << 8)); }
static void write32(Uint8 *p, Uint32 n)
{
    int i;
    for (i = 0; i < 4; ++i) p[i] = (Uint8)(n >> (i * 8));
}

static void push_state(AuraSlot *slot)
{
    static const Uint8 hats[9] = {SDL_HAT_UP, SDL_HAT_RIGHTUP, SDL_HAT_RIGHT,
        SDL_HAT_RIGHTDOWN, SDL_HAT_DOWN, SDL_HAT_LEFTDOWN, SDL_HAT_LEFT,
        SDL_HAT_LEFTUP, SDL_HAT_CENTERED};
    int i;
    if (!slot->open) return;
    for (i = 0; i < slot->buttons; ++i)
        SDL_PrivateJoystickButton(slot->open, i, (slot->pressed >> i) & 1);
    SDL_PrivateJoystickHat(slot->open, 0, hats[slot->hat < 8 ? slot->hat : 8]);
    for (i = 0; i < 6; ++i) SDL_PrivateJoystickAxis(slot->open, i, slot->axes[i]);
}

static void remove_slot(AuraSlot *slot)
{
    if (!slot->present) return;
    slot->present = SDL_FALSE;
    SDL_PrivateJoystickRemoved(slot->instance);
    slot->open = NULL;
}

/* Validate the entire discovery response before changing any devices. */
static void discovery(const Uint8 *data, size_t size)
{
    int i;
    if (size < 190) return;
    for (i = 0; i < SLOT_COUNT; ++i) {
        const Uint8 *p = data + 1 + i * 60;
        if (size < (size_t)(i * 60 + 10) || p[0] > 1) return;
        if (p[0] && ((p[1] != 10 && p[1] != 12) || p[2] > 1 || p[3] > 1 ||
                     p[8] > 48 || size < (size_t)(i * 60 + 10 + p[8]))) return;
    }
    last_reply = SDL_GetTicks();
    for (i = 0; i < SLOT_COUNT; ++i) {
        const Uint8 *p = data + 1 + i * 60;
        AuraSlot *slot = &slots[i];
        if (!p[0]) { remove_slot(slot); continue; }
        if (slot->present && slot->buttons == p[1] && slot->axis_mode == p[2] &&
            slot->vibration == p[3] && slot->vid == read16(p + 4) &&
            slot->pid == read16(p + 6) && SDL_strlen(slot->name) == p[8] &&
            !SDL_memcmp(slot->name, p + 9, p[8])) continue;
        remove_slot(slot);
        SDL_zero(*slot);
        slot->buttons = p[1]; slot->axis_mode = p[2]; slot->vibration = p[3];
        slot->vid = read16(p + 4); slot->pid = read16(p + 6);
        SDL_memcpy(slot->name, p + 9, p[8]);
        slot->hat = 255;
        slot->axes[4] = slot->axes[5] = SDL_MIN_SINT16;
        slot->instance = SDL_GetNextJoystickInstanceID();
        slot->present = SDL_TRUE;
        SDL_PrivateJoystickAdded(slot->instance);
    }
}

static void state_packet(const Uint8 *p, size_t size)
{
    int i;
    AuraSlot *slot;
    if (size < 13 || p[1] >= SLOT_COUNT) return;
    slot = &slots[p[1]];
    if (!slot->present || size < (slot->axis_mode ? 17u : 13u) ||
        (p[4] > 7 && p[4] != 255)) return;
    slot->pressed = read16(p + 2); slot->hat = p[4];
    for (i = 0; i < 4; ++i) slot->axes[i] = (Sint16)read16(p + 5 + i * 2);
    for (i = 4; i < 6; ++i) {
        int value = slot->axis_mode ? (Sint16)read16(p + 5 + i * 2) :
                    ((slot->pressed & (1 << (i + 6))) ? 32767 : 0);
        value = SDL_clamp(value, 0, 32767);
        slot->axes[i] = value == 32767 ? 32767 : value * 2 - 32768;
    }
    push_state(slot); /* Preserve down/up edges within one SDL pump. */
}

static void AURA_Detect(void)
{
    Uint8 packet[257];
    Uint32 now = SDL_GetTicks();
    int i;
    if (channel < 0) return;
    if ((Uint32)(now - last_query) >= DISCOVERY_MS) {
        const Uint8 request = 8; /* GET_GAMEPAD; reply returns to our ephemeral port. */
        send(channel, &request, 1, 0);
        last_query = now;
    }
    for (i = 0; i < 128; ++i) { /* Bound work even if a local sender floods the socket. */
        ssize_t n = recv(channel, packet, sizeof(packet), MSG_DONTWAIT);
        if (n < 0) break;
        if (!n || n > 256) continue;
        if (packet[0] == 8) discovery(packet, (size_t)n);
        else if (packet[0] == 9) state_packet(packet, (size_t)n);
    }
    if ((Uint32)(SDL_GetTicks() - last_reply) > TIMEOUT_MS)
        for (i = 0; i < SLOT_COUNT; ++i) remove_slot(&slots[i]);
}

static int AURA_Init(void)
{
    struct sockaddr_in host;
    SDL_zero(host); SDL_zero(slots);
    host.sin_family = AF_INET; host.sin_port = htons(HOST_PORT);
    host.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    channel = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (channel < 0) return SDL_SetError("Aura gamepad socket: %s", strerror(errno));
    if (connect(channel, (struct sockaddr *)&host, sizeof(host)) < 0) {
        close(channel); channel = -1;
        return SDL_SetError("Aura gamepad connect: %s", strerror(errno));
    }
    last_reply = SDL_GetTicks(); last_query = last_reply - DISCOVERY_MS;
    AURA_Detect();
    return 0;
}

static int slot_at(int index)
{
    int i;
    for (i = 0; i < SLOT_COUNT; ++i) if (slots[i].present && index-- == 0) return i;
    return -1;
}
static int AURA_Count(void)
{
    int i, count = 0;
    for (i = 0; i < SLOT_COUNT; ++i) count += slots[i].present;
    return count;
}
static const char *AURA_Name(int index) { return slots[slot_at(index)].name; }
static const char *AURA_Path(int index) { return NULL; }
static int AURA_SteamSlot(int index) { return -1; }
static int AURA_Player(int index) { return slot_at(index); }
static void AURA_SetPlayer(int index, int player) { /* Slot assignment belongs to Aura. */ }
static SDL_JoystickGUID AURA_GUID(int index)
{
    AuraSlot *s = &slots[slot_at(index)];
    /* Virtual signature selects our backend mapping instead of Android keycodes. */
    return SDL_CreateJoystickGUID(SDL_HARDWARE_BUS_VIRTUAL, s->vid, s->pid, 1,
                                  "Aura", s->name, 'v', SDL_JOYSTICK_TYPE_GAMECONTROLLER);
}
static SDL_JoystickID AURA_Instance(int index) { return slots[slot_at(index)].instance; }
static int AURA_Open(SDL_Joystick *joystick, int index)
{
    int slot = slot_at(index);
    if (slot < 0) return SDL_SetError("Aura gamepad disconnected");
    joystick->hwdata = SDL_calloc(1, sizeof(*joystick->hwdata));
    if (!joystick->hwdata) return SDL_OutOfMemory();
    joystick->hwdata->slot = slot; joystick->hwdata->instance = slots[slot].instance;
    joystick->naxes = 6; joystick->nhats = 1; joystick->nbuttons = slots[slot].buttons;
    slots[slot].open = joystick;
    return 0;
}
static void AURA_Update(SDL_Joystick *joystick)
{
    AuraSlot *slot = &slots[joystick->hwdata->slot];
    if (slot->present && slot->instance == joystick->hwdata->instance) push_state(slot);
}
static void AURA_Close(SDL_Joystick *joystick)
{
    AuraSlot *slot = &slots[joystick->hwdata->slot];
    if (slot->open == joystick) slot->open = NULL;
    SDL_free(joystick->hwdata); joystick->hwdata = NULL;
}
static int AURA_Rumble(SDL_Joystick *joystick, Uint16 low, Uint16 high)
{
    Uint8 p[14] = {19}; /* SET_GAMEPAD_STATE */
    AuraSlot *slot = &slots[joystick->hwdata->slot];
    if (!slot->present || !slot->vibration || channel < 0) return SDL_Unsupported();
    p[1] = joystick->hwdata->slot;
    write32(p + 2, low); write32(p + 6, high);
    /* SDL refreshes prolonged effects and sends a zero command at expiry. */
    write32(p + 10, low || high ? 5000 : 0);
    return send(channel, p, sizeof(p), 0) == sizeof(p) ? 0 : -1;
}
static int AURA_RumbleTriggers(SDL_Joystick *j, Uint16 l, Uint16 r) { return SDL_Unsupported(); }
static Uint32 AURA_Capabilities(SDL_Joystick *j)
{ return slots[j->hwdata->slot].vibration ? SDL_JOYCAP_RUMBLE : 0; }
static int AURA_LED(SDL_Joystick *j, Uint8 r, Uint8 g, Uint8 b) { return SDL_Unsupported(); }
static int AURA_Effect(SDL_Joystick *j, const void *data, int size) { return SDL_Unsupported(); }
static int AURA_Sensors(SDL_Joystick *j, SDL_bool enabled) { return SDL_Unsupported(); }
static void AURA_Quit(void)
{
    const Uint8 release = 10;
    if (channel >= 0) { send(channel, &release, 1, 0); close(channel); channel = -1; }
    SDL_zero(slots);
}
static SDL_bool AURA_Mapping(int index, SDL_GamepadMapping *out)
{
    SDL_zero(*out);
#define BUTTON(name, n) out->name.kind = EMappingKind_Button; out->name.target = n
#define AXIS(name, n) out->name.kind = EMappingKind_Axis; out->name.target = n
#define HAT(name, n) out->name.kind = EMappingKind_Hat; out->name.target = n
    BUTTON(a, 0); BUTTON(b, 1); BUTTON(x, 2); BUTTON(y, 3);
    BUTTON(leftshoulder, 4); BUTTON(rightshoulder, 5); BUTTON(back, 6); BUTTON(start, 7);
    BUTTON(leftstick, 8); BUTTON(rightstick, 9);
    AXIS(leftx, 0); AXIS(lefty, 1); AXIS(rightx, 2); AXIS(righty, 3);
    AXIS(lefttrigger, 4); AXIS(righttrigger, 5);
    HAT(dpup, SDL_HAT_UP); HAT(dpdown, SDL_HAT_DOWN);
    HAT(dpleft, SDL_HAT_LEFT); HAT(dpright, SDL_HAT_RIGHT);
    return SDL_TRUE;
}

/* The isolated build selects this backend in SDL's otherwise empty dummy slot. */
SDL_JoystickDriver SDL_DUMMY_JoystickDriver = {
    AURA_Init, AURA_Count, AURA_Detect, AURA_Name, AURA_Path, AURA_SteamSlot,
    AURA_Player, AURA_SetPlayer, AURA_GUID, AURA_Instance, AURA_Open,
    AURA_Rumble, AURA_RumbleTriggers, AURA_Capabilities, AURA_LED, AURA_Effect,
    AURA_Sensors, AURA_Update, AURA_Close, AURA_Quit, AURA_Mapping
};
