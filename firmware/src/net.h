#pragma once
#include "audio.h"
#include "ota.h"
#include "wifi.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define NET_MAX_ITEMS 18
#define NET_MAX_TODOS 30
#define NET_TODO_ID_LEN 16

typedef struct {
    char title[64];
    char when[24];
    char summary[200];
    char text[200];
    int todos, marks;
    bool ready;
} net_item_t;

typedef struct {
    char id[NET_TODO_ID_LEN];
    char text[96];
    char from[64];
    bool done;
} net_todo_t;

#define HUB_FRAME_WEATHER 8
#define HUB_FRAME_AGENDA 9
#define HUB_FRAME_DISPLAY 14
#define NET_WX_HOURS 13
#define NET_WX_MARKS 5
#define NET_WX_DAYS 5
#define NET_AGENDA_MAX 12

typedef enum { WX_SUN, WX_CLOUD, WX_RAIN, WX_STORM, WX_SNOW, WX_FOG, WX_COUNT } wx_cond_t;
typedef enum { FACE_DIAL, FACE_DIGITS, FACE_WORDS, FACE_NOTE, FACE_FLIP, FACE_COUNT } clock_face_t;
typedef enum { WXS_SKETCH, WXS_BIG, WXS_JOURNAL, WXS_WEEK, WXS_COUNT } weather_style_t;

typedef struct {
    char place[32];
    bool from_phone, fahrenheit, valid;
    int temp, feels, hi, lo, rain, hour;
    uint8_t cond;
    int temps[NET_WX_HOURS];
    uint8_t conds[NET_WX_MARKS];
    int days;
    struct {
        char wd[4];
        uint8_t cond;
        int lo, hi;
    } day[NET_WX_DAYS];
} net_weather_t;

typedef struct {
    bool show;
    int count;
    struct {
        uint8_t day;
        char start[6];
        char title[49];
    } ev[NET_AGENDA_MAX];
} net_agenda_t;

typedef enum { UPDATE_OFF, UPDATE_DOWNLOAD, UPDATE_RECEIVE, UPDATE_INSTALL, UPDATE_DONE } update_phase_t;
typedef enum { UPDATE_IDLE, UPDATE_CHECKING, UPDATE_NONE, UPDATE_FAILED, UPDATE_LOW_BATTERY, UPDATE_INSTALL_FAILED } update_check_t;

typedef struct {
    wifi_status_t wifi;
    bool hub;
    char hub_host[64];
    int count;
    net_item_t items[NET_MAX_ITEMS];
    bool todos_loaded;
    int open_todos;
    int todo_count;
    net_todo_t todos[NET_MAX_TODOS];
    char update_version[OTA_VERSION_LEN];
    int update_progress;
    uint8_t update_check;
    uint8_t update_phase;
    net_weather_t weather;
    net_agenda_t agenda;
    uint8_t clock_face, weather_style;
    bool hour12, buzz_from_hub, sound_from_hub, home_from_hub, night_from_hub, night_on;
    unsigned short night_start, night_end;
    uint8_t home, sound;
    unsigned short buzz_hz;
    bool phone;
    unsigned version;
} net_state_t;

void net_init(void);
void net_enqueue(audio_chunk_t *chunk);
void net_set_telemetry(int battery_pct, int voltage_mv, int current_ma, bool recording, bool usb);
void net_install_update(void);
void net_get_state(net_state_t *out);
void net_set_todo(const char *id, bool done);
void net_test_hub(void);
void net_forget_wifi(const char *ssid);
void net_post_screen(const uint8_t *panel_fb, size_t len);
void net_hub_frame(uint8_t type, const char *json, size_t len);
void net_weather_set(bool from_phone, bool fahrenheit);
void net_checkin_soon(void);
bool net_mark(void);
void net_set_sound(int sound);
void net_set_buzz_hz(int hz);
void net_buzz_test(int hz);
int net_take_buzz_test(void);
void net_set_home(int home);
void net_set_boot(const char *text);
void net_set_night(bool on, int start, int end);
void net_ota_frame(uint8_t type, const uint8_t *p, size_t n);
void net_check_updates(void);
void net_cancel_update(void);
void net_set_climate(bool ok, int room_c10, int humidity);
void net_lists_frame(const char *json, size_t len);
void net_need_wifi(void);
bool net_park(uint32_t timeout_ms);
void net_set_hub_token(const char *hex);

