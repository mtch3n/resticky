#include "ui.h"
#include "art.h"
#include "ble.h"
#include "buzzer.h"
#include <ctype.h>
#include <math.h>
#include "epd.h"
#include "esp_app_desc.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lvgl.h"
#include "splash.h"
#include "sdcard.h"
#include "spool.h"
#include "touch.h"
#include <stdio.h>
#include <string.h>
#include <time.h>

#define UI_W 480
#define UI_H 800
#define STATUS_H 44
#define TOP 68
#define ROW_H 64
#define PAGER_Y (UI_H - 96)
#define PARTIALS_PER_FULL 20
#define BOOT_TIMEOUT_MS 20000
#define BOOT_READY_HOLD_MS 800
#define TODO_ROW_H 66
#define TODO_PAGE 8
#define LIST_PAGE 9
#define HISTORY 24
#define HISTORY_S 3600
#define CLOCK_VALID_AFTER 1735689600

LV_FONT_DECLARE(icons_96);
LV_FONT_DECLARE(icons_64);
LV_FONT_DECLARE(icons_34);
LV_FONT_DECLARE(icons_28);
LV_FONT_DECLARE(text_16);
LV_FONT_DECLARE(text_18);
LV_FONT_DECLARE(text_20);
LV_FONT_DECLARE(title_22);
LV_FONT_DECLARE(title_28);
LV_FONT_DECLARE(title_40);
LV_FONT_DECLARE(clock_112);
LV_FONT_DECLARE(label_16);
LV_FONT_DECLARE(body_18);
LV_FONT_DECLARE(body_20);
LV_FONT_DECLARE(hand_28);
LV_FONT_DECLARE(hand_40);
LV_FONT_DECLARE(hand_64);
LV_FONT_DECLARE(hand_88);
LV_FONT_DECLARE(hand_128);
LV_FONT_DECLARE(hand_150);
LV_FONT_DECLARE(hand_170);
LV_FONT_DECLARE(hand_200);

#define ICON_MIC "\xEE\xAB\xB0"
#define ICON_MIC_OFF "\xEE\xB4\x96"
#define ICON_SETTINGS "\xEE\xAC\xA0"
#define ICON_WIFI "\xEE\xAD\x92"
#define ICON_WIFI_OFF "\xEE\xB3\xBA"
#define ICON_BATTERY_CHARGING "\xEE\xA8\xB3"
#define ICON_FILE "\xEE\xAA\xA2"
#define ICON_CHART "\xEE\xA9\x99"
#define ICON_PREV "\xEE\xA9\xA0"
#define ICON_NEXT "\xEE\xA9\xA1"
#define ICON_MOON "\xEE\xAB\xB8"
#define ICON_DEVICE "\xEE\xB3\x9B"
#define ICON_LIST_CHECK "\xEE\xAD\xAA"
#define ICON_CHECK "\xEE\xA9\x9E"
#define ICON_HAND "\xEE\xBA\x94"
#define ICON_CLOUD "\xEE\xA9\xB6"
static const char *const BATTERY_LEVELS[] = {"\xEE\xA8\xB4", "\xEE\xA8\xAF", "\xEE\xA8\xB0", "\xEE\xA8\xB1", "\xEE\xA8\xB2"};
static const char *const WIFI_LEVELS[] = {"\xEE\xAE\xA3", "\xEE\xAE\xA4", "\xEE\xAE\xA5", "\xEE\xAD\x92"};
static const char *const ACTION_NAMES[ACT_COUNT] = {"Nothing", "Sleep / wake", "Mute / record", "Previous page", "Next page", "Home", "Mark moment"};
static const char *const SLOT_NAMES[SLOT_COUNT] = {"POWER \xC2\xB7 PRESS", "POWER \xC2\xB7 DOUBLE PRESS", "UP \xC2\xB7 PRESS", "UP \xC2\xB7 HOLD", "DOWN \xC2\xB7 PRESS", "DOWN \xC2\xB7 HOLD", "BACK \xC2\xB7 DOUBLE TAP"};
static const int QUALITIES[3] = {8, 12, 16};

typedef struct {
    lv_obj_t *bar, *prev, *next, *label;
    int page, pages;
    void (*render)(void);
} pager_t;

typedef struct view {
    lv_obj_t *scr;
    const char *title;
    struct view *parent;
    pager_t *pager;
    void (*on_show)(void);
} view_t;

typedef struct {
    lv_obj_t *track, *knob, *text;
} switch_t;

typedef struct {
    lv_obj_t *box, *parts[4];
    int count;
} seg_t;

static const char *TAG = "ui";
static uint8_t *panel_fb, *shown_fb;
static int partials = PARTIALS_PER_FULL;
static bool force_full, splash_on = true, sleeping;
static int boot_shown;
static uint32_t boot_ready_ms;
static ui_actions_t actions;
static settings_t current;
static sensors_t last;
EXT_RAM_BSS_ATTR static net_state_t view;
static bool recording = true;
static int64_t rec_since_us;
static int shown_minute = -1;
static unsigned weather_shown;
RTC_DATA_ATTR static int8_t history[HISTORY];
RTC_DATA_ATTR static int history_len;
RTC_DATA_ATTR static time_t history_at;

static view_t v_launcher, v_recorder, v_todo, v_transcripts, v_clock, v_weather, v_wsettings, v_stats, v_settings, v_rec, v_wifi, v_hub,
    v_buttons, v_choose, v_device, v_pair, v_off, v_map, v_sd;
static view_t *current_view;
static pager_t todo_pager, list_pager, stats_pager;

static lv_obj_t *status, *status_title, *status_home, *status_wifi, *status_batt, *status_batt_icon;
static lv_obj_t *rec_tile, *rec_tile_icon, *todo_badge, *list_badge, *clock_tile_time;
static lv_obj_t *rec_dot, *rec_state, *rec_sub, *last_box, *last_title, *last_text;
static lv_obj_t *list_rows[LIST_PAGE], *list_titles[LIST_PAGE], *list_subs[LIST_PAGE], *list_empty;
static lv_obj_t *todo_hdr, *todo_done_hdr, *todo_rows[TODO_PAGE], *todo_boxes[TODO_PAGE], *todo_checks[TODO_PAGE];
static lv_obj_t *todo_texts[TODO_PAGE], *todo_froms[TODO_PAGE];
static char todo_ids[TODO_PAGE][NET_TODO_ID_LEN];
static bool todo_done[TODO_PAGE];
static lv_obj_t *weather_tile_icon, *weather_tile_temp, *weather_value;
static lv_obj_t *stats_pages[2], *stats_big, *stats_sub, *chart_line, *device_values[9];
static lv_point_precise_t chart_points[HISTORY];
static lv_obj_t *rec_value, *wifi_value, *hub_value, *buttons_value, *sd_value;
static seg_t quality;
static switch_t mic_switch, flip_switch, beep_switch;
static lv_obj_t *net_rows[WIFI_MAX_PROFILES], *net_names[WIFI_MAX_PROFILES], *net_states[WIFI_MAX_PROFILES], *net_empty;
static char net_ssids[WIFI_MAX_PROFILES][WIFI_SSID_LEN];
static lv_obj_t *hub_name, *hub_status, *update_button;
static lv_obj_t *slot_values[SLOT_COUNT], *choose_title, *choose_checks[ACT_COUNT];
static int choose_slot;
static lv_obj_t *pair_code;

static uint32_t tick_ms(void) { return esp_timer_get_time() / 1000; }

static lv_obj_t *toast;
static uint8_t *photo_in;
static volatile bool photo_pending;
static bool photo_on;
static uint32_t toast_until;

static void refresh_panel(bool full)
{
    int64_t t = esp_timer_get_time();
    epd_refresh(panel_fb, full);
    if (!splash_on) net_post_screen(panel_fb, EPD_FB_SIZE);
    memcpy(shown_fb, panel_fb, EPD_FB_SIZE);
    ESP_LOGI(TAG, "%s frame shown in %lld ms", full ? "full" : "partial", (esp_timer_get_time() - t) / 1000);
}

static void show_panel(void)
{
    if (!force_full && memcmp(panel_fb, shown_fb, EPD_FB_SIZE) == 0) return;
    bool full = force_full || partials >= PARTIALS_PER_FULL;
    partials = full ? 0 : partials + 1;
    force_full = false;
    refresh_panel(full);
}

static void flush(lv_display_t *disp, const lv_area_t *area, uint8_t *px)
{
    if (splash_on || photo_on) {
        lv_display_flush_ready(disp);
        return;
    }
    memset(panel_fb, 0xFF, EPD_FB_SIZE);
    for (int y = 0; y < UI_H; y++) {
        const uint8_t *row = px + y * UI_W;
        for (int x = 0; x < UI_W; x++)
            if (row[x] < 128) panel_fb[(UI_W - 1 - x) * (EPD_W / 8) + y / 8] &= ~(0x80 >> (y % 8));
    }
    show_panel();
    lv_display_flush_ready(disp);
}

static void show_splash(int frame)
{
    memcpy(panel_fb, splash_frames[frame], EPD_FB_SIZE);
    show_panel();
}

static void end_photo(void);

static void touch_cb(lv_indev_t *indev, lv_indev_data_t *data)
{
    int x, y;
    static bool was_pressed;
    bool pressed = touch_read(&x, &y);
    if (pressed && !was_pressed) buzzer_click();
    if (pressed && photo_on) {
        end_photo();
        pressed = false;
        lv_indev_wait_release(indev);
    }
    was_pressed = pressed;
    data->state = pressed ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
    data->point.x = x;
    data->point.y = y;
}

static lv_obj_t *label(lv_obj_t *parent, const char *text, const lv_font_t *font)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_label_set_text(l, text);
    lv_obj_set_style_text_font(l, font, 0);
    return l;
}

static lv_obj_t *plain(lv_obj_t *parent)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_remove_style_all(o);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE | LV_OBJ_FLAG_CLICKABLE);
    return o;
}

static void set_hidden(lv_obj_t *o, bool hidden)
{
    if (hidden) lv_obj_add_flag(o, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_remove_flag(o, LV_OBJ_FLAG_HIDDEN);
}

static void set_inverted(lv_obj_t *o, bool black)
{
    lv_obj_set_style_bg_color(o, black ? lv_color_black() : lv_color_white(), 0);
    lv_obj_set_style_text_color(o, black ? lv_color_white() : lv_color_black(), 0);
}

static void show_view(view_t *v)
{
    current_view = v;
    if (v->on_show) v->on_show();
    set_hidden(status, v == &v_off);
    set_hidden(status_title, !v->title);
    set_hidden(status_home, !v->title || v == &v_pair);
    if (v->title) lv_label_set_text(status_title, v->title);
    lv_screen_load(v->scr);
}

static void pager_step(pager_t *p, int step)
{
    int page = LV_CLAMP(0, p->page + step, p->pages - 1);
    if (page == p->page) return;
    p->page = page;
    p->render();
}

static void go_back(void)
{
    if (current_view->parent) show_view(current_view->parent);
}

static void gesture_cb(lv_event_t *e)
{
    view_t *v = lv_event_get_user_data(e);
    lv_dir_t dir = lv_indev_get_gesture_dir(lv_indev_active());
    lv_indev_wait_release(lv_indev_active());
    if (dir == LV_DIR_LEFT && v->pager) pager_step(v->pager, 1);
    else if (dir == LV_DIR_RIGHT && v->pager && v->pager->page > 0) pager_step(v->pager, -1);
    else if (dir == LV_DIR_RIGHT) go_back();
}

static lv_obj_t *screen(view_t *v, const char *title, view_t *parent)
{
    lv_obj_t *s = lv_obj_create(NULL);
    lv_obj_remove_flag(s, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_bg_color(s, lv_color_white(), 0);
    lv_obj_set_style_text_color(s, lv_color_black(), 0);
    lv_obj_set_style_pad_all(s, 0, 0);
    lv_obj_add_event_cb(s, gesture_cb, LV_EVENT_GESTURE, v);
    v->scr = s;
    v->title = title;
    v->parent = parent;
    return s;
}

static void go_cb(lv_event_t *e) { show_view(lv_event_get_user_data(e)); }

static void home_cb(lv_event_t *e) { show_view(&v_launcher); }

static lv_obj_t *status_row(lv_obj_t *parent, lv_align_t align)
{
    lv_obj_t *r = plain(parent);
    lv_obj_set_size(r, LV_SIZE_CONTENT, STATUS_H);
    lv_obj_set_flex_flow(r, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(r, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(r, 8, 0);
    lv_obj_align(r, align, align == LV_ALIGN_TOP_LEFT ? 12 : -12, 0);
    return r;
}

static void build_status(void)
{
    status = plain(lv_layer_top());
    lv_obj_set_size(status, UI_W, STATUS_H);
    lv_obj_t *left = status_row(status, LV_ALIGN_TOP_LEFT), *right = status_row(status, LV_ALIGN_TOP_RIGHT);
    status_wifi = label(left, ICON_WIFI_OFF, &icons_28);
    status_title = label(left, "", &title_22);
    status_batt = label(right, "--%", &text_18);
    status_batt_icon = label(right, BATTERY_LEVELS[0], &icons_28);
    status_home = plain(lv_layer_top());
    lv_obj_set_size(status_home, 320, 56);
    lv_obj_add_flag(status_home, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(status_home, home_cb, LV_EVENT_CLICKED, NULL);
}

static lv_obj_t *round_button(lv_obj_t *parent, const char *icon, lv_align_t align, int x)
{
    lv_obj_t *b = plain(parent);
    lv_obj_set_size(b, 64, 64);
    lv_obj_align(b, align, x, 0);
    lv_obj_set_style_radius(b, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_border_width(b, 3, 0);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_center(label(b, icon, &icons_34));
    return b;
}

static void pager_click(lv_event_t *e)
{
    pager_t *p = lv_event_get_user_data(e);
    pager_step(p, lv_event_get_target(e) == p->next ? 1 : -1);
}

static void pager_init(view_t *v, pager_t *p, void (*render)(void))
{
    v->pager = p;
    p->render = render;
    p->bar = plain(v->scr);
    lv_obj_set_size(p->bar, UI_W, 96);
    lv_obj_set_pos(p->bar, 0, PAGER_Y);
    p->prev = round_button(p->bar, ICON_PREV, LV_ALIGN_LEFT_MID, 24);
    p->next = round_button(p->bar, ICON_NEXT, LV_ALIGN_RIGHT_MID, -24);
    lv_obj_add_event_cb(p->prev, pager_click, LV_EVENT_CLICKED, p);
    lv_obj_add_event_cb(p->next, pager_click, LV_EVENT_CLICKED, p);
    p->label = label(p->bar, "", &title_22);
    lv_obj_center(p->label);
}

static int pager_set(pager_t *p, int count, int per_page)
{
    p->pages = count > 0 ? (count + per_page - 1) / per_page : 1;
    p->page = LV_CLAMP(0, p->page, p->pages - 1);
    set_hidden(p->bar, p->pages < 2);
    lv_label_set_text_fmt(p->label, "%d / %d", p->page + 1, p->pages);
    set_hidden(p->prev, p->page == 0);
    set_hidden(p->next, p->page == p->pages - 1);
    return p->page * per_page;
}

void ui_show_photo(const uint8_t *bits, size_t len)
{
    if (len != UI_W * UI_H / 8 || photo_pending) return;
    if (!photo_in) photo_in = heap_caps_malloc(len, MALLOC_CAP_SPIRAM);
    if (!photo_in) return;
    memcpy(photo_in, bits, len);
    photo_pending = true;
}

static void draw_photo(void)
{
    memset(panel_fb, 0xFF, EPD_FB_SIZE);
    for (int y = 0; y < UI_H; y++)
        for (int x = 0; x < UI_W; x++)
            if (photo_in[(y * UI_W + x) >> 3] & (0x80 >> (x & 7))) panel_fb[(UI_W - 1 - x) * (EPD_W / 8) + y / 8] &= ~(0x80 >> (y % 8));
    photo_pending = false;
    photo_on = true;
    force_full = true;
    show_panel();
    ESP_LOGI(TAG, "photo shown");
}

static void end_photo(void)
{
    photo_on = false;
    force_full = true;
    lv_obj_invalidate(lv_screen_active());
}

void ui_show_mark(bool ok)
{
    if (!toast) {
        toast = lv_obj_create(lv_layer_top());
        lv_obj_remove_style_all(toast);
        lv_obj_set_size(toast, 300, 72);
        lv_obj_align(toast, LV_ALIGN_BOTTOM_MID, 0, -40);
        lv_obj_set_style_radius(toast, 36, 0);
        lv_obj_set_style_bg_opa(toast, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(toast, lv_color_black(), 0);
        lv_obj_set_style_text_color(toast, lv_color_white(), 0);
        lv_obj_center(label(toast, "", &text_20));
    }
    time_t now = time(NULL);
    struct tm t;
    localtime_r(&now, &t);
    lv_obj_t *l = lv_obj_get_child(toast, 0);
    if (ok) lv_label_set_text_fmt(l, "\xE2\x98\x85 marked %02d:%02d", t.tm_hour, t.tm_min);
    else lv_label_set_text(l, "Not recording");
    lv_obj_center(l);
    lv_obj_remove_flag(toast, LV_OBJ_FLAG_HIDDEN);
    toast_until = tick_ms() + 3000;
}

void ui_key(action_t action)
{
    if (photo_on) return end_photo();
    if (splash_on || !current_view) return;
    if (action == ACT_HOME) show_view(&v_launcher);
    else if (action == ACT_PREV && current_view->pager) pager_step(current_view->pager, -1);
    else if (action == ACT_NEXT && current_view->pager) pager_step(current_view->pager, 1);
}

static lv_obj_t *section(lv_obj_t *parent, const char *text, int y)
{
    lv_obj_t *l = label(parent, text, &label_16);
    lv_obj_set_style_text_letter_space(l, 1, 0);
    lv_obj_set_pos(l, 24, y);
    return l;
}

static lv_obj_t *row(lv_obj_t *parent, int y, const char *name)
{
    lv_obj_t *r = plain(parent);
    lv_obj_set_size(r, UI_W, ROW_H);
    lv_obj_set_pos(r, 0, y);
    lv_obj_align(label(r, name, &body_20), LV_ALIGN_LEFT_MID, 24, 0);
    return r;
}

static lv_obj_t *row_value(lv_obj_t *r, int right)
{
    lv_obj_t *v = label(r, "", &body_18);
    lv_obj_align(v, LV_ALIGN_RIGHT_MID, right, 0);
    return v;
}

static lv_obj_t *nav_row(lv_obj_t *parent, int y, const char *icon, const char *name, view_t *to)
{
    lv_obj_t *r = plain(parent);
    lv_obj_set_size(r, UI_W, ROW_H);
    lv_obj_set_pos(r, 0, y);
    lv_obj_add_flag(r, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(r, go_cb, LV_EVENT_CLICKED, to);
    if (icon) lv_obj_align(label(r, icon, &icons_34), LV_ALIGN_LEFT_MID, 22, 0);
    lv_obj_align(label(r, name, &body_20), LV_ALIGN_LEFT_MID, icon ? 70 : 24, 0);
    lv_obj_align(label(r, ICON_NEXT, &icons_34), LV_ALIGN_RIGHT_MID, -14, 0);
    return row_value(r, -56);
}

static lv_obj_t *button(lv_obj_t *parent, const char *text, int y, bool primary, lv_event_cb_t cb)
{
    lv_obj_t *b = plain(parent);
    lv_obj_set_size(b, UI_W - 48, 64);
    lv_obj_set_pos(b, 24, y);
    lv_obj_set_style_radius(b, 32, 0);
    lv_obj_set_style_border_width(b, 3, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    set_inverted(b, primary);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(b, cb, LV_EVENT_CLICKED, NULL);
    lv_obj_center(label(b, text, &text_20));
    return b;
}

static void switch_create(switch_t *sw, lv_obj_t *r)
{
    sw->track = plain(r);
    lv_obj_set_size(sw->track, 104, 48);
    lv_obj_align(sw->track, LV_ALIGN_RIGHT_MID, -24, 0);
    lv_obj_set_style_radius(sw->track, 24, 0);
    lv_obj_set_style_border_width(sw->track, 3, 0);
    lv_obj_set_style_bg_opa(sw->track, LV_OPA_COVER, 0);
    sw->knob = plain(sw->track);
    lv_obj_set_size(sw->knob, 32, 32);
    lv_obj_set_style_radius(sw->knob, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(sw->knob, LV_OPA_COVER, 0);
    sw->text = label(sw->track, "", &text_16);
}

static void switch_set(switch_t *sw, bool on)
{
    set_inverted(sw->track, on);
    lv_obj_set_style_bg_color(sw->knob, on ? lv_color_white() : lv_color_black(), 0);
    lv_obj_align(sw->knob, on ? LV_ALIGN_RIGHT_MID : LV_ALIGN_LEFT_MID, on ? -5 : 5, 0);
    lv_label_set_text(sw->text, on ? "ON" : "OFF");
    lv_obj_align(sw->text, on ? LV_ALIGN_LEFT_MID : LV_ALIGN_RIGHT_MID, on ? 14 : -12, 0);
}

static lv_obj_t *badge(lv_obj_t *parent, lv_obj_t *tile)
{
    lv_obj_t *b = plain(parent);
    lv_obj_set_size(b, LV_SIZE_CONTENT, 40);
    lv_obj_set_style_min_width(b, 40, 0);
    lv_obj_set_style_pad_hor(b, 8, 0);
    lv_obj_set_style_radius(b, 20, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(b, 3, 0);
    lv_obj_set_style_border_color(b, lv_color_white(), 0);
    set_inverted(b, true);
    lv_obj_t *t = label(b, "", &text_18);
    lv_obj_center(t);
    lv_obj_align_to(b, tile, LV_ALIGN_TOP_RIGHT, 12, -12);
    lv_obj_add_flag(b, LV_OBJ_FLAG_HIDDEN);
    return b;
}

static void set_badge(lv_obj_t *b, lv_obj_t *tile, int n)
{
    set_hidden(b, n <= 0);
    if (n <= 0) return;
    lv_label_set_text_fmt(lv_obj_get_child(b, 0), "%d", n);
    lv_obj_update_layout(b);
    lv_obj_align_to(b, tile, LV_ALIGN_TOP_RIGHT, 12, -12);
}

static void build_launcher(void)
{
    lv_obj_t *s = screen(&v_launcher, NULL, NULL);
    const struct { const char *icon; const char *name; view_t *to; } apps[] = {
        {ICON_MIC, "Recorder", &v_recorder}, {ICON_LIST_CHECK, "To-do", &v_todo}, {ICON_FILE, "Transcripts", &v_transcripts},
        {NULL, "Clock", &v_clock},           {NULL, "Weather", &v_weather},   {ICON_CHART, "Stats", &v_stats},
        {ICON_SETTINGS, "Settings", &v_settings},
    };
    lv_obj_t *tiles[7];
    for (int i = 0; i < 7; i++) {
        int x = 34 + (i % 3) * 148, y = 64 + (i / 3) * 166;
        lv_obj_t *t = plain(s);
        lv_obj_set_size(t, 104, 104);
        lv_obj_set_pos(t, x, y);
        lv_obj_set_style_radius(t, 28, 0);
        lv_obj_set_style_border_width(t, 3, 0);
        lv_obj_set_style_bg_opa(t, LV_OPA_COVER, 0);
        set_inverted(t, false);
        lv_obj_add_flag(t, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_event_cb(t, go_cb, LV_EVENT_CLICKED, apps[i].to);
        lv_obj_t *n = label(s, apps[i].name, &text_20);
        lv_obj_set_width(n, 160);
        lv_obj_set_style_text_align(n, LV_TEXT_ALIGN_CENTER, 0);
        lv_obj_set_pos(n, x - 28, y + 112);
        tiles[i] = t;
        if (apps[i].to == &v_weather) {
            weather_tile_icon = lv_image_create(t);
            lv_image_set_src(weather_tile_icon, &art_wx_cloud_s);
            lv_obj_align(weather_tile_icon, LV_ALIGN_TOP_MID, 0, 4);
            weather_tile_temp = label(t, "--", &title_22);
            lv_obj_align(weather_tile_temp, LV_ALIGN_BOTTOM_MID, 0, -6);
            continue;
        }
        lv_obj_t *ic = label(t, apps[i].icon ? apps[i].icon : "--:--", apps[i].icon ? &icons_64 : &title_28);
        lv_obj_center(ic);
        if (i == 0) rec_tile_icon = ic;
        if (apps[i].to == &v_clock) clock_tile_time = ic;
    }
    rec_tile = tiles[0];
    todo_badge = badge(s, tiles[1]);
    list_badge = badge(s, tiles[2]);
    lv_obj_set_user_data(todo_badge, tiles[1]);
    lv_obj_set_user_data(list_badge, tiles[2]);
}

static void show_recorder(void);

static void build_recorder(void)
{
    lv_obj_t *s = screen(&v_recorder, "Recorder", &v_launcher);
    v_recorder.on_show = show_recorder;
    rec_dot = plain(s);
    lv_obj_set_size(rec_dot, 16, 16);
    lv_obj_set_pos(rec_dot, 24, TOP + 12);
    lv_obj_set_style_radius(rec_dot, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(rec_dot, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(rec_dot, lv_color_black(), 0);
    rec_state = label(s, "", &title_28);
    rec_sub = label(s, "", &text_18);
    lv_obj_set_pos(rec_sub, 24, TOP + 42);
    last_box = plain(s);
    lv_obj_set_size(last_box, UI_W - 40, 220);
    lv_obj_set_pos(last_box, 20, TOP + 92);
    lv_obj_set_style_border_width(last_box, 3, 0);
    lv_obj_set_style_radius(last_box, 24, 0);
    lv_obj_set_style_pad_all(last_box, 20, 0);
    lv_obj_add_flag(last_box, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_event_cb(last_box, go_cb, LV_EVENT_CLICKED, &v_transcripts);
    last_title = label(last_box, "", &text_16);
    last_text = label(last_box, "", &text_20);
    lv_obj_set_width(last_text, UI_W - 86);
    lv_label_set_long_mode(last_text, LV_LABEL_LONG_DOT);
    lv_obj_set_height(last_text, 140);
    lv_obj_set_pos(last_text, 0, 30);
}

static void show_recorder(void)
{
    int minutes = (esp_timer_get_time() - rec_since_us) / 60000000;
    set_hidden(rec_dot, !recording);
    lv_label_set_text(rec_state, recording ? "Recording" : "Muted");
    lv_obj_set_pos(rec_state, recording ? 50 : 24, TOP);
    if (minutes >= 60) lv_label_set_text_fmt(rec_sub, "for %d h %02d", minutes / 60, minutes % 60);
    else lv_label_set_text_fmt(rec_sub, "for %d min", minutes);
}

static void render_transcripts(void);

static void build_transcripts(void)
{
    lv_obj_t *s = screen(&v_transcripts, "Transcripts", &v_launcher);
    list_empty = label(s, "No transcripts yet", &text_20);
    lv_obj_set_pos(list_empty, 24, TOP);
    for (int i = 0; i < LIST_PAGE; i++) {
        lv_obj_t *r = plain(s);
        lv_obj_set_size(r, UI_W, ROW_H);
        lv_obj_set_pos(r, 0, TOP - 8 + i * ROW_H);
        list_titles[i] = label(r, "", &text_20);
        list_subs[i] = label(r, "", &text_16);
        for (int k = 0; k < 2; k++) {
            lv_obj_t *l = k ? list_subs[i] : list_titles[i];
            lv_obj_set_width(l, UI_W - 48);
            lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
            lv_obj_set_height(l, k ? 22 : 26);
            lv_obj_set_pos(l, 24, k ? 34 : 8);
        }
        set_hidden(r, true);
        list_rows[i] = r;
    }
    pager_init(&v_transcripts, &list_pager, render_transcripts);
}

static void render_transcripts(void)
{
    const net_state_t *n = &view;
    set_hidden(list_empty, n->count > 0);
    int first = pager_set(&list_pager, n->count, LIST_PAGE);
    for (int r = 0; r < LIST_PAGE; r++) {
        int i = first + r;
        set_hidden(list_rows[r], i >= n->count);
        if (i >= n->count) continue;
        const net_item_t *it = &n->items[i];
        lv_label_set_text_fmt(list_titles[r], "%s \xC2\xB7 %s", it->when, it->ready ? it->title : "Live");
        if (it->ready && it->marks) lv_label_set_text_fmt(list_subs[r], "%d to-do%s \xC2\xB7 \xE2\x98\x85 %d", it->todos, it->todos == 1 ? "" : "s", it->marks);
        else if (it->ready) lv_label_set_text_fmt(list_subs[r], "%d to-do%s", it->todos, it->todos == 1 ? "" : "s");
        else lv_label_set_text(list_subs[r], it->text);
    }
}

static void todo_clicked(lv_event_t *e)
{
    int r = (int)(intptr_t)lv_event_get_user_data(e);
    actions.set_todo(todo_ids[r], !todo_done[r]);
}

static void render_todos(void);

static void build_todo(void)
{
    lv_obj_t *s = screen(&v_todo, "To-do", &v_launcher);
    todo_hdr = section(s, "No to-dos yet", TOP);
    todo_done_hdr = label(s, "", &text_16);
    for (int i = 0; i < TODO_PAGE; i++) {
        lv_obj_t *r = plain(s);
        lv_obj_set_size(r, UI_W, TODO_ROW_H);
        lv_obj_add_flag(r, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_event_cb(r, todo_clicked, LV_EVENT_CLICKED, (void *)(intptr_t)i);
        lv_obj_t *box = plain(r);
        lv_obj_set_size(box, 40, 40);
        lv_obj_align(box, LV_ALIGN_LEFT_MID, 24, 0);
        lv_obj_set_style_border_width(box, 3, 0);
        lv_obj_set_style_radius(box, 10, 0);
        lv_obj_set_style_bg_opa(box, LV_OPA_COVER, 0);
        lv_obj_remove_flag(box, LV_OBJ_FLAG_CLICKABLE);
        todo_checks[i] = label(box, ICON_CHECK, &icons_34);
        lv_obj_set_style_text_color(todo_checks[i], lv_color_white(), 0);
        lv_obj_center(todo_checks[i]);
        todo_texts[i] = label(r, "", &text_20);
        todo_froms[i] = label(r, "", &text_16);
        for (int k = 0; k < 2; k++) {
            lv_obj_t *l = k ? todo_froms[i] : todo_texts[i];
            lv_obj_set_width(l, UI_W - 108);
            lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
            lv_obj_set_height(l, k ? 22 : 26);
            lv_obj_set_pos(l, 84, k ? 38 : 10);
        }
        todo_boxes[i] = box;
        todo_rows[i] = r;
    }
    pager_init(&v_todo, &todo_pager, render_todos);
}

static void render_todos(void)
{
    const net_state_t *n = &view;
    if (n->todos_loaded) lv_label_set_text_fmt(todo_hdr, "OPEN \xC2\xB7 %d", n->open_todos);
    lv_obj_add_flag(todo_done_hdr, LV_OBJ_FLAG_HIDDEN);
    int done = 0;
    for (int i = 0; i < n->todo_count; i++) done += n->todos[i].done;
    int first = pager_set(&todo_pager, n->todo_count, TODO_PAGE), y = TOP + 28;
    for (int r = 0; r < TODO_PAGE; r++) {
        int i = first + r;
        set_hidden(todo_rows[r], i >= n->todo_count);
        if (i >= n->todo_count) continue;
        const net_todo_t *t = &n->todos[i];
        if (t->done && lv_obj_has_flag(todo_done_hdr, LV_OBJ_FLAG_HIDDEN) && y + 38 + TODO_ROW_H <= PAGER_Y) {
            lv_label_set_text_fmt(todo_done_hdr, "DONE TODAY \xC2\xB7 %d", done);
            lv_obj_set_pos(todo_done_hdr, 24, y + 10);
            lv_obj_remove_flag(todo_done_hdr, LV_OBJ_FLAG_HIDDEN);
            y += 38;
        }
        if (y + TODO_ROW_H > PAGER_Y) {
            set_hidden(todo_rows[r], true);
            continue;
        }
        strlcpy(todo_ids[r], t->id, sizeof todo_ids[r]);
        todo_done[r] = t->done;
        lv_label_set_text(todo_texts[r], t->text);
        lv_label_set_text(todo_froms[r], t->from);
        lv_obj_set_style_bg_color(todo_boxes[r], t->done ? lv_color_black() : lv_color_white(), 0);
        set_hidden(todo_checks[r], !t->done);
        lv_obj_set_pos(todo_rows[r], 0, y);
        y += TODO_ROW_H;
    }
}

static bool clock_valid(void) { return time(NULL) > CLOCK_VALID_AFTER; }

static const char *const HOUR_WORDS[12] = {"twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven"};
static const char *const MINUTE_WORDS[12] = {"", "five", "ten", "quarter", "twenty", "twenty-five", "half", "twenty-five", "twenty", "quarter", "ten", "five"};

typedef struct {
    lv_obj_t *box, *time, *date, *words[3], *flip[4], *hour_hand, *min_hand;
    lv_point_precise_t hour_pts[2], min_pts[2];
} face_t;

EXT_RAM_BSS_ATTR static face_t faces[FACE_COUNT];
static lv_obj_t *agenda_room, *agenda_box, *agenda_head, *agenda_dots[5], *agenda_times[5], *agenda_titles[5], *agenda_empty;
static const int AGENDA_TOP[FACE_COUNT] = {446, 400, 476, 400, 412};

static lv_obj_t *centered(lv_obj_t *parent, const lv_font_t *font, int y)
{
    lv_obj_t *l = label(parent, "", font);
    lv_obj_set_width(l, UI_W);
    lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(l, 0, y);
    return l;
}

static lv_obj_t *image(lv_obj_t *parent, const lv_image_dsc_t *src, int x, int y)
{
    lv_obj_t *i = lv_image_create(parent);
    lv_image_set_src(i, src);
    lv_obj_set_pos(i, x, y);
    return i;
}

static lv_obj_t *thick_line(lv_obj_t *parent, lv_point_precise_t *pts, int width)
{
    lv_obj_t *l = lv_line_create(parent);
    lv_line_set_points(l, pts, 2);
    lv_obj_set_style_line_width(l, width, 0);
    lv_obj_set_style_line_rounded(l, true, 0);
    return l;
}

static lv_obj_t *dot(lv_obj_t *parent, int size, int x, int y)
{
    lv_obj_t *d = plain(parent);
    lv_obj_set_size(d, size, size);
    lv_obj_set_pos(d, x, y);
    lv_obj_set_style_radius(d, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(d, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(d, lv_color_black(), 0);
    return d;
}

static void build_agenda(lv_obj_t *s)
{
    agenda_box = plain(s);
    lv_obj_set_size(agenda_box, UI_W, UI_H - 400);
    agenda_head = label(agenda_box, "today", &hand_28);
    lv_obj_set_pos(agenda_head, 24, 0);
    agenda_room = label(agenda_box, "", &hand_28);
    lv_obj_align(agenda_room, LV_ALIGN_TOP_RIGHT, -24, 0);
    agenda_empty = label(agenda_box, "nothing on the calendar", &hand_28);
    lv_obj_set_pos(agenda_empty, 56, 44);
    for (int i = 0; i < 5; i++) {
        agenda_dots[i] = dot(agenda_box, 10, 31, 58 + i * 50);
        agenda_times[i] = label(agenda_box, "", &hand_28);
        lv_obj_set_pos(agenda_times[i], 56, 42 + i * 50);
        agenda_titles[i] = label(agenda_box, "", &hand_28);
        lv_obj_set_width(agenda_titles[i], UI_W - 160);
        lv_label_set_long_mode(agenda_titles[i], LV_LABEL_LONG_DOT);
        lv_obj_set_pos(agenda_titles[i], 140, 42 + i * 50);
    }
}

static void render_agenda(int face)
{
    if (last.climate_ok) lv_label_set_text_fmt(agenda_room, "inside %d\xC2\xB0 \xC2\xB7 %d%%", (last.room_c10 + 5) / 10, last.humidity);
    const net_agenda_t *a = &view.agenda;
    lv_obj_set_y(agenda_box, AGENDA_TOP[face]);
    set_hidden(agenda_box, !a->show);
    int rows = (UI_H - AGENDA_TOP[face] - 44) / 50, shown = 0;
    for (int i = 0; i < a->count && shown < rows && shown < 5; i++) {
        if (a->ev[i].day != 0) continue;
        lv_label_set_text(agenda_times[shown], a->ev[i].start[0] ? a->ev[i].start : "all day");
        lv_label_set_text(agenda_titles[shown], a->ev[i].title);
        shown++;
    }
    for (int i = 0; i < 5; i++) {
        set_hidden(agenda_dots[i], i >= shown);
        set_hidden(agenda_times[i], i >= shown);
        set_hidden(agenda_titles[i], i >= shown);
    }
    set_hidden(agenda_empty, shown > 0);
}

static void set_hand(face_t *f, lv_obj_t *hand, lv_point_precise_t *pts, float deg, int len)
{
    float a = (deg - 90) * 3.14159265f / 180;
    pts[0] = (lv_point_precise_t){240, 226};
    pts[1] = (lv_point_precise_t){240 + len * cosf(a), 226 + len * sinf(a)};
    lv_line_set_points(hand, pts, 2);
}

static void show_clock(void)
{
    time_t now = time(NULL);
    struct tm t;
    localtime_r(&now, &t);
    bool ok = clock_valid();
    char hm[8] = "--:--", date[40] = "waiting for the time", short_date[24] = "";
    if (ok) {
        if (view.hour12) snprintf(hm, sizeof hm, "%d:%02d", t.tm_hour % 12 ? t.tm_hour % 12 : 12, t.tm_min);
        else strftime(hm, sizeof hm, "%H:%M", &t);
        strftime(date, sizeof date, "%A, %e %B", &t);
        strftime(short_date, sizeof short_date, "%a, %e %b", &t);
        for (char *c = date; *c; c++) *c = tolower((unsigned char)*c);
        for (char *c = short_date; *c; c++) *c = tolower((unsigned char)*c);
    }
    lv_label_set_text(clock_tile_time, hm);
    int face = view.clock_face < FACE_COUNT ? view.clock_face : FACE_DIAL;
    for (int i = 0; i < FACE_COUNT; i++) set_hidden(faces[i].box, i != face);
    face_t *f = &faces[face];
    if (f->date) lv_label_set_text(f->date, face == FACE_NOTE ? short_date : date);
    if (f->time) lv_label_set_text(f->time, hm);
    if (face == FACE_DIAL) {
        set_hand(f, f->hour_hand, f->hour_pts, ((t.tm_hour % 12) + t.tm_min / 60.0f) * 30, 78);
        set_hand(f, f->min_hand, f->min_pts, t.tm_min * 6, 120);
    }
    if (face == FACE_FLIP)
        for (int i = 0; i < 4; i++) {
            char padded[8];
            snprintf(padded, sizeof padded, "%5s", hm);
            char d[2] = {ok ? (padded[i < 2 ? i : i + 1] == ' ' ? 0 : padded[i < 2 ? i : i + 1]) : '-', 0};
            lv_label_set_text(f->flip[i], d);
        }
    if (face == FACE_WORDS) {
        int step = (t.tm_min + 2) / 5 % 12, hour = (t.tm_hour + (t.tm_min + 2 >= 35 ? 1 : 0)) % 12;
        bool exact = t.tm_min % 5 == 0;
        lv_label_set_text(f->words[0], !ok ? "" : exact ? "it's" : "it's about");
        if (step == 0) {
            lv_label_set_text(f->words[1], HOUR_WORDS[hour]);
            lv_label_set_text(f->words[2], "o'clock");
        } else {
            lv_label_set_text(f->words[1], MINUTE_WORDS[step]);
            lv_label_set_text_fmt(f->words[2], "%s %s", step < 7 ? "past" : "to", HOUR_WORDS[hour]);
        }
    }
    render_agenda(face);
    shown_minute = ok ? t.tm_hour * 60 + t.tm_min : -1;
}

static void build_clock(void)
{
    lv_obj_t *s = screen(&v_clock, "Clock", &v_launcher);
    v_clock.on_show = show_clock;
    for (int i = 0; i < FACE_COUNT; i++) {
        faces[i].box = plain(s);
        lv_obj_set_size(faces[i].box, UI_W, UI_H);
    }
    face_t *f = &faces[FACE_DIAL];
    image(f->box, &art_dial, 80, 66);
    f->hour_hand = thick_line(f->box, f->hour_pts, 10);
    f->min_hand = thick_line(f->box, f->min_pts, 6);
    lv_obj_t *hub = dot(f->box, 16, 232, 218);
    lv_obj_set_style_border_width(hub, 3, 0);
    lv_obj_set_style_bg_color(hub, lv_color_white(), 0);
    f->date = centered(f->box, &hand_40, 392);

    f = &faces[FACE_DIGITS];
    image(f->box, &art_sun_small, 14, 38);
    f->time = centered(f->box, &hand_200, 70);
    image(f->box, &art_underline2, 50, 270);
    f->date = centered(f->box, &hand_40, 306);

    f = &faces[FACE_WORDS];
    f->words[0] = centered(f->box, &hand_40, 84);
    f->words[1] = centered(f->box, &hand_88, 140);
    f->words[2] = centered(f->box, &hand_88, 250);
    image(f->box, &art_underline, 60, 366);
    f->date = centered(f->box, &hand_28, 410);

    f = &faces[FACE_NOTE];
    image(f->box, &art_note, 40, 50);
    f->time = label(f->box, "", &hand_150);
    lv_obj_set_width(f->time, 360);
    lv_obj_set_style_text_align(f->time, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(f->time, 56, 92);
    f->date = label(f->box, "", &hand_40);
    lv_obj_set_width(f->date, 360);
    lv_obj_set_style_text_align(f->date, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(f->date, 56, 268);

    f = &faces[FACE_FLIP];
    static lv_point_precise_t hinges[4][2];
    const int xs[4] = {22, 130, 250, 358};
    for (int i = 0; i < 4; i++) {
        image(f->box, &art_flip_card, xs[i], 66);
        f->flip[i] = label(f->box, "", &hand_170);
        lv_obj_set_width(f->flip[i], 104);
        lv_obj_set_style_text_align(f->flip[i], LV_TEXT_ALIGN_CENTER, 0);
        lv_obj_set_pos(f->flip[i], xs[i] - 4, 86);
        hinges[i][0] = (lv_point_precise_t){xs[i], 194};
        hinges[i][1] = (lv_point_precise_t){xs[i] + 106, 194};
        thick_line(f->box, hinges[i], 3);
    }
    dot(f->box, 10, 235, 159);
    dot(f->box, 10, 235, 219);
    f->date = centered(f->box, &hand_40, 344);
    build_agenda(s);
}

static const lv_image_dsc_t *const WX_ART[WX_COUNT][4] = {
    {&art_wx_sun_s, &art_wx_sun_m, &art_wx_sun_l, &art_wx_sun_xl},
    {&art_wx_cloud_s, &art_wx_cloud_m, &art_wx_cloud_l, &art_wx_cloud_xl},
    {&art_wx_rain_s, &art_wx_rain_m, &art_wx_rain_l, &art_wx_rain_xl},
    {&art_wx_storm_s, &art_wx_storm_m, &art_wx_storm_l, &art_wx_storm_xl},
    {&art_wx_snow_s, &art_wx_snow_m, &art_wx_snow_l, &art_wx_snow_xl},
    {&art_wx_fog_s, &art_wx_fog_m, &art_wx_fog_l, &art_wx_fog_xl},
};
static const char *const WX_NAMES[WX_COUNT] = {"sunny", "cloudy", "rain", "storms", "snow", "fog"};
enum { WS_S, WS_M, WS_L, WS_XL };

typedef struct {
    lv_obj_t *box, *hero, *temp, *line1, *line2, *line3, *line4, *line5, *line6;
    lv_obj_t *day[NET_WX_DAYS], *day_icon[NET_WX_DAYS], *day_lo[NET_WX_DAYS], *day_hi[NET_WX_DAYS], *bar[NET_WX_DAYS];
    lv_obj_t *curve, *marks[NET_WX_MARKS], *mark_temp[NET_WX_MARKS], *mark_time[NET_WX_MARKS];
} wx_view_t;

EXT_RAM_BSS_ATTR static wx_view_t wx[WXS_COUNT];
static lv_point_precise_t curve_pts[NET_WX_HOURS];
static lv_obj_t *wx_empty;

static const lv_image_dsc_t *wx_art(int cond, int size) { return WX_ART[cond < WX_COUNT ? cond : WX_CLOUD][size]; }

static void build_weather(void)
{
    lv_obj_t *s = screen(&v_weather, "Weather", &v_launcher);
    for (int i = 0; i < WXS_COUNT; i++) {
        wx[i].box = plain(s);
        lv_obj_set_size(wx[i].box, UI_W, UI_H);
    }
    wx_empty = centered(s, &hand_40, 360);
    wx_view_t *w = &wx[WXS_SKETCH];
    w->hero = image(w->box, &art_wx_cloud_l, 0, 52);
    w->temp = label(w->box, "", &hand_128);
    lv_obj_set_pos(w->temp, 252, 44);
    w->line1 = label(w->box, "", &hand_28);
    lv_obj_set_pos(w->line1, 256, 186);
    w->line2 = label(w->box, "", &hand_28);
    lv_obj_set_pos(w->line2, 256, 222);
    w->curve = lv_line_create(w->box);
    lv_obj_set_style_line_width(w->curve, 4, 0);
    lv_obj_set_style_line_rounded(w->curve, true, 0);
    for (int k = 0; k < NET_WX_MARKS; k++) {
        w->marks[k] = dot(w->box, 12, 0, 0);
        w->mark_temp[k] = label(w->box, "", &hand_28);
        w->mark_time[k] = label(w->box, "", &hand_28);
    }
    for (int i = 0; i < NET_WX_DAYS; i++) {
        int y = 520 + i * 50;
        w->day[i] = label(w->box, "", &hand_40);
        lv_obj_set_pos(w->day[i], 24, y - 6);
        w->day_icon[i] = image(w->box, &art_wx_cloud_s, 102, y - 8);
        w->day_lo[i] = label(w->box, "", &hand_28);
        lv_obj_set_width(w->day_lo[i], 56);
        lv_obj_set_style_text_align(w->day_lo[i], LV_TEXT_ALIGN_RIGHT, 0);
        lv_obj_set_pos(w->day_lo[i], 196, y);
        lv_obj_t *track = plain(w->box);
        lv_obj_set_size(track, 140, 2);
        lv_obj_set_pos(track, 262, y + 20);
        lv_obj_set_style_bg_opa(track, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(track, lv_color_black(), 0);
        w->bar[i] = plain(w->box);
        lv_obj_set_style_radius(w->bar[i], 6, 0);
        lv_obj_set_style_bg_opa(w->bar[i], LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(w->bar[i], lv_color_black(), 0);
        w->day_hi[i] = label(w->box, "", &hand_28);
        lv_obj_set_pos(w->day_hi[i], 412, y);
    }

    w = &wx[WXS_BIG];
    w->hero = image(w->box, &art_wx_cloud_xl, 6, 40);
    w->temp = centered(w->box, &hand_128, 380);
    w->line1 = centered(w->box, &hand_28, 530);
    for (int i = 0; i < NET_WX_DAYS; i++) {
        int x = 48 + i * 96;
        w->day[i] = label(w->box, "", &hand_28);
        lv_obj_set_width(w->day[i], 96);
        lv_obj_set_style_text_align(w->day[i], LV_TEXT_ALIGN_CENTER, 0);
        lv_obj_set_pos(w->day[i], x - 48, 604);
        w->day_icon[i] = image(w->box, &art_wx_cloud_s, x - 36, 650);
        w->day_hi[i] = label(w->box, "", &hand_28);
        lv_obj_set_width(w->day_hi[i], 96);
        lv_obj_set_style_text_align(w->day_hi[i], LV_TEXT_ALIGN_CENTER, 0);
        lv_obj_set_pos(w->day_hi[i], x - 48, 728);
    }

    w = &wx[WXS_JOURNAL];
    image(w->box, &art_ruled, 0, 60);
    lv_obj_t **lines[6] = {&w->line1, &w->line2, &w->line3, &w->line4, &w->line5, &w->line6};
    for (int k = 0; k < 6; k++) {
        *lines[k] = label(w->box, "", k < 2 ? &hand_40 : &hand_28);
        lv_obj_set_pos(*lines[k], 84, (k < 2 ? 70 : 82) + k * 52);
    }
    lv_obj_set_pos(label(w->box, "this week", &hand_28), 84, 446);
    for (int i = 0; i < NET_WX_DAYS; i++) {
        int y = 498 + i * 52;
        w->day[i] = label(w->box, "", &hand_28);
        lv_obj_set_pos(w->day[i], 84, y);
        w->day_icon[i] = image(w->box, &art_wx_cloud_s, 136, y - 22);
        w->day_hi[i] = label(w->box, "", &hand_28);
        lv_obj_set_pos(w->day_hi[i], 214, y);
    }

    w = &wx[WXS_WEEK];
    w->hero = image(w->box, &art_wx_cloud_m, 30, 56);
    w->temp = label(w->box, "", &hand_128);
    lv_obj_set_pos(w->temp, 180, 40);
    w->line1 = label(w->box, "", &hand_28);
    lv_obj_set_width(w->line1, 120);
    lv_obj_set_style_text_align(w->line1, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(w->line1, 346, 84);
    w->line2 = label(w->box, "", &hand_28);
    lv_obj_set_width(w->line2, 120);
    lv_obj_set_style_text_align(w->line2, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(w->line2, 346, 120);
    for (int i = 0; i < NET_WX_DAYS; i++) {
        int x = 14 + (i % 2) * 230, y = 228 + i / 2 * 186, cw = i == 4 ? 452 : 222;
        image(w->box, i == 4 ? &art_card_full : &art_card_half, x - 2, y - 2);
        w->day[i] = label(w->box, "", &hand_40);
        lv_obj_set_pos(w->day[i], x + 20, y + 8);
        w->day_icon[i] = image(w->box, &art_wx_cloud_m, x + cw - 126, y + 6);
        w->day_hi[i] = label(w->box, "", &hand_64);
        lv_obj_set_pos(w->day_hi[i], x + 18, y + 86);
        w->day_lo[i] = label(w->box, "", &hand_28);
        lv_obj_set_pos(w->day_lo[i], x + 110, y + 112);
    }
}

static void show_weather(void)
{
    const net_weather_t *n = &view.weather;
    int style = view.weather_style < WXS_COUNT ? view.weather_style : WXS_SKETCH;
    for (int i = 0; i < WXS_COUNT; i++) set_hidden(wx[i].box, !n->valid || i != style);
    set_hidden(wx_empty, n->valid);
    lv_label_set_text(wx_empty, n->from_phone ? "allow location on the phone" : "waiting for the forecast");
    lv_image_set_src(weather_tile_icon, wx_art(n->valid ? n->cond : WX_CLOUD, WS_S));
    if (n->valid) lv_label_set_text_fmt(weather_tile_temp, "%d\xC2\xB0", n->temp);
    if (!n->valid) return;
    wx_view_t *w = &wx[style];
    const char *name = WX_NAMES[n->cond < WX_COUNT ? n->cond : WX_CLOUD];
    int lo = n->days ? n->day[0].lo : n->lo, hi = n->days ? n->day[0].hi : n->hi;
    for (int i = 0; i < n->days; i++) {
        lo = LV_MIN(lo, n->day[i].lo);
        hi = LV_MAX(hi, n->day[i].hi);
    }
    if (style == WXS_SKETCH) {
        lv_image_set_src(w->hero, wx_art(n->cond, WS_L));
        lv_label_set_text_fmt(w->temp, "%d\xC2\xB0", n->temp);
        lv_label_set_text_fmt(w->line1, "%s \xC2\xB7 feels %d\xC2\xB0", name, n->feels);
        lv_label_set_text_fmt(w->line2, "%d\xC2\xB0 / %d\xC2\xB0 \xC2\xB7 rain %d%%", n->hi, n->lo, n->rain);
        int tmin = n->temps[0], tmax = n->temps[0];
        for (int k = 0; k < NET_WX_HOURS; k++) {
            tmin = LV_MIN(tmin, n->temps[k]);
            tmax = LV_MAX(tmax, n->temps[k]);
        }
        int span = LV_MAX(tmax - tmin, 1);
        for (int k = 0; k < NET_WX_HOURS; k++) {
            curve_pts[k].x = 44 + k * 33;
            curve_pts[k].y = 432 - (n->temps[k] - tmin) * 90 / span;
        }
        lv_line_set_points(w->curve, curve_pts, NET_WX_HOURS);
        for (int m = 0; m < NET_WX_MARKS; m++) {
            int k = m * 3, x = curve_pts[k].x, y = curve_pts[k].y;
            lv_obj_set_pos(w->marks[m], x - 6, y - 6);
            lv_label_set_text_fmt(w->mark_temp[m], "%d\xC2\xB0", n->temps[k]);
            lv_obj_set_pos(w->mark_temp[m], x - 18, y - 40);
            if (m == 0) lv_label_set_text(w->mark_time[m], "now");
            else lv_label_set_text_fmt(w->mark_time[m], "%02d:00", (n->hour + k) % 24);
            lv_obj_set_pos(w->mark_time[m], x - 26, 460);
        }
        int range = LV_MAX(hi - lo, 1);
        for (int i = 0; i < NET_WX_DAYS; i++) {
            bool on = i < n->days;
            set_hidden(w->day[i], !on);
            set_hidden(w->day_icon[i], !on);
            set_hidden(w->day_lo[i], !on);
            set_hidden(w->day_hi[i], !on);
            set_hidden(w->bar[i], !on);
            if (!on) continue;
            lv_label_set_text(w->day[i], n->day[i].wd);
            lv_image_set_src(w->day_icon[i], wx_art(n->day[i].cond, WS_S));
            lv_label_set_text_fmt(w->day_lo[i], "%d\xC2\xB0", n->day[i].lo);
            lv_label_set_text_fmt(w->day_hi[i], "%d\xC2\xB0", n->day[i].hi);
            int a = 262 + (n->day[i].lo - lo) * 140 / range, b = 262 + (n->day[i].hi - lo) * 140 / range;
            lv_obj_set_pos(w->bar[i], a, 520 + i * 50 + 15);
            lv_obj_set_size(w->bar[i], LV_MAX(b - a, 12), 12);
        }
    } else if (style == WXS_BIG) {
        lv_image_set_src(w->hero, wx_art(n->cond, WS_XL));
        lv_label_set_text_fmt(w->temp, "%d\xC2\xB0", n->temp);
        lv_label_set_text_fmt(w->line1, "%s \xC2\xB7 feels %d\xC2\xB0 \xC2\xB7 rain %d%%", name, n->feels, n->rain);
        for (int i = 0; i < NET_WX_DAYS; i++) {
            bool on = i < n->days;
            set_hidden(w->day[i], !on);
            set_hidden(w->day_icon[i], !on);
            set_hidden(w->day_hi[i], !on);
            if (!on) continue;
            lv_label_set_text(w->day[i], n->day[i].wd);
            lv_image_set_src(w->day_icon[i], wx_art(n->day[i].cond, WS_S));
            lv_label_set_text_fmt(w->day_hi[i], "%d\xC2\xB0 %d\xC2\xB0", n->day[i].hi, n->day[i].lo);
        }
    } else if (style == WXS_JOURNAL) {
        time_t now = time(NULL);
        struct tm t;
        localtime_r(&now, &t);
        char date[40];
        strftime(date, sizeof date, "%A, %e %B", &t);
        for (char *c = date; *c; c++) *c = tolower((unsigned char)*c);
        int later = n->temps[NET_WX_HOURS - 1];
        lv_label_set_text(w->line1, date);
        lv_label_set_text_fmt(w->line2, "%s, %d\xC2\xB0.", name, n->temp);
        lv_label_set_text_fmt(w->line3, "feels like %d\xC2\xB0.", n->feels);
        lv_label_set_text_fmt(w->line4, "high %d\xC2\xB0, low %d\xC2\xB0.", n->hi, n->lo);
        lv_label_set_text_fmt(w->line5, "%d%% chance of rain.", n->rain);
        lv_label_set_text_fmt(w->line6, "%s to %d\xC2\xB0 by tonight.", later < n->temp ? "cooling" : later > n->temp ? "warming" : "staying", later);
        for (int i = 0; i < NET_WX_DAYS; i++) {
            bool on = i < n->days;
            set_hidden(w->day[i], !on);
            set_hidden(w->day_icon[i], !on);
            set_hidden(w->day_hi[i], !on);
            if (!on) continue;
            lv_label_set_text(w->day[i], n->day[i].wd);
            lv_image_set_src(w->day_icon[i], wx_art(n->day[i].cond, WS_S));
            lv_label_set_text_fmt(w->day_hi[i], "%s, %d\xC2\xB0 to %d\xC2\xB0", WX_NAMES[n->day[i].cond % WX_COUNT], n->day[i].lo, n->day[i].hi);
        }
    } else {
        lv_image_set_src(w->hero, wx_art(n->cond, WS_M));
        lv_label_set_text_fmt(w->temp, "%d\xC2\xB0", n->temp);
        lv_label_set_text(w->line1, name);
        lv_label_set_text_fmt(w->line2, "%d\xC2\xB0 / %d\xC2\xB0", n->hi, n->lo);
        for (int i = 0; i < NET_WX_DAYS; i++) {
            bool on = i < n->days;
            set_hidden(w->day[i], !on);
            set_hidden(w->day_icon[i], !on);
            set_hidden(w->day_hi[i], !on);
            set_hidden(w->day_lo[i], !on);
            if (!on) continue;
            lv_label_set_text(w->day[i], n->day[i].wd);
            lv_image_set_src(w->day_icon[i], wx_art(n->day[i].cond, WS_M));
            lv_label_set_text_fmt(w->day_hi[i], "%d\xC2\xB0", n->day[i].hi);
            lv_label_set_text_fmt(w->day_lo[i], "%d\xC2\xB0", n->day[i].lo);
        }
    }
}


static seg_t wx_mode_seg, wx_unit_seg;
static lv_obj_t *wx_now_value;

static void seg_create(seg_t *sg, lv_obj_t *parent, int y, const char *const *names, int count, lv_event_cb_t cb)
{
    sg->count = count;
    sg->box = plain(parent);
    lv_obj_set_size(sg->box, UI_W - 48, 64);
    lv_obj_set_pos(sg->box, 24, y);
    lv_obj_set_style_radius(sg->box, 32, 0);
    lv_obj_set_style_border_width(sg->box, 3, 0);
    int w = (UI_W - 48 - 6 - 6) / count;
    for (int i = 0; i < count; i++) {
        lv_obj_t *p = plain(sg->box);
        lv_obj_set_size(p, w, 52);
        lv_obj_set_pos(p, 3 + i * w, 3);
        lv_obj_set_style_radius(p, 26, 0);
        lv_obj_add_flag(p, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_event_cb(p, cb, LV_EVENT_CLICKED, (void *)(intptr_t)i);
        lv_obj_center(label(p, names[i], &text_18));
        sg->parts[i] = p;
    }
}

static void seg_set(seg_t *sg, int on)
{
    for (int i = 0; i < sg->count; i++) {
        lv_obj_set_style_bg_opa(sg->parts[i], i == on ? LV_OPA_COVER : LV_OPA_TRANSP, 0);
        lv_obj_set_style_bg_color(sg->parts[i], lv_color_black(), 0);
        lv_obj_set_style_text_color(sg->parts[i], i == on ? lv_color_white() : lv_color_black(), 0);
    }
}

static void show_weather_settings(void)
{
    const net_weather_t *n = &view.weather;
    seg_set(&wx_mode_seg, n->from_phone ? 0 : 1);
    seg_set(&wx_unit_seg, n->fahrenheit ? 1 : 0);
    if (n->place[0]) lv_label_set_text_fmt(wx_now_value, "%s \xC2\xB7 %s", n->place, n->from_phone ? "from phone" : "fixed");
    else lv_label_set_text(wx_now_value, "-");
    lv_label_set_text(weather_value, n->from_phone ? "Phone" : "City");
}

static void wx_mode_clicked(lv_event_t *e)
{
    bool phone = (int)(intptr_t)lv_event_get_user_data(e) == 0;
    net_weather_set(phone, view.weather.fahrenheit);
    view.weather.from_phone = phone;
    show_weather_settings();
}

static void wx_unit_clicked(lv_event_t *e)
{
    bool f = (int)(intptr_t)lv_event_get_user_data(e) == 1;
    net_weather_set(view.weather.from_phone, f);
    view.weather.fahrenheit = f;
    show_weather_settings();
}

static void build_weather_settings(void)
{
    lv_obj_t *s = screen(&v_wsettings, "Weather", &v_settings);
    v_wsettings.on_show = show_weather_settings;
    static const char *const modes[] = {"From phone", "Fixed city"}, *const units[] = {"\xC2\xB0" "C", "\xC2\xB0" "F"};
    section(s, "LOCATION", TOP);
    seg_create(&wx_mode_seg, s, TOP + 28, modes, 2, wx_mode_clicked);
    wx_now_value = row_value(row(s, TOP + 104, "Now"), -24);
    section(s, "UNITS", TOP + 188);
    seg_create(&wx_unit_seg, s, TOP + 216, units, 2, wx_unit_clicked);
    lv_obj_t *hint = label(s, "Set a fixed city in the phone app: Settings \xE2\x80\xBA Weather.", &body_18);
    lv_obj_set_width(hint, UI_W - 48);
    lv_label_set_long_mode(hint, LV_LABEL_LONG_WRAP);
    lv_obj_set_pos(hint, 24, TOP + 300);
}

static void render_stats(void)
{
    pager_set(&stats_pager, 2, 1);
    set_hidden(stats_pages[0], stats_pager.page != 0);
    set_hidden(stats_pages[1], stats_pager.page != 1);
    if (!last.gauge_ok) {
        lv_label_set_text(stats_big, "--");
        lv_label_set_text(stats_sub, "Battery gauge not available");
    } else if (last.usb) {
        lv_label_set_text_fmt(stats_big, "%d%%", last.soc_pct);
        lv_label_set_text_fmt(stats_sub, "charging \xC2\xB7 %d of %d mAh", last.remaining_mah, last.full_mah);
    } else {
        int draw = last.avg_current_ma < 0 ? -last.avg_current_ma : last.avg_current_ma;
        int tenths = draw > 0 ? last.remaining_mah * 10 / draw / 24 : 0;
        lv_label_set_text_fmt(stats_big, "%d.%d days", tenths / 10, tenths % 10);
        lv_label_set_text_fmt(stats_sub, "battery left \xC2\xB7 %d%% \xC2\xB7 %d mA", last.soc_pct, draw);
    }
    for (int i = 0; i < history_len; i++) {
        chart_points[i].x = 40 + (HISTORY - history_len + i) * 392 / (HISTORY - 1);
        chart_points[i].y = 8 + (100 - history[i]) * 134 / 100;
    }
    lv_line_set_points(chart_line, chart_points, history_len);
    int64_t up = esp_timer_get_time() / 1000000;
    spool_stats_t sp;
    spool_stats(&sp);
    lv_label_set_text_fmt(device_values[0], "%s \xC2\xB7 up %lld d %lld h", esp_app_get_description()->version, up / 86400, up / 3600 % 24);
    lv_label_set_text_fmt(device_values[1], "240 MHz \xC2\xB7 %d \xC2\xB0" "C", sensors_chip_celsius());
    lv_label_set_text_fmt(device_values[2], "%u KB free \xC2\xB7 lowest %u KB",
                          (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
                          (unsigned)(heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL) / 1024));
    lv_label_set_text_fmt(device_values[3], "%u.%u of %u MB free", (unsigned)(heap_caps_get_free_size(MALLOC_CAP_SPIRAM) >> 20),
                          (unsigned)((heap_caps_get_free_size(MALLOC_CAP_SPIRAM) >> 10) % 1024 * 10 / 1024),
                          (unsigned)(heap_caps_get_total_size(MALLOC_CAP_SPIRAM) >> 20));
    lv_label_set_text_fmt(device_values[4], "%lu chunks \xC2\xB7 %lu KB wait", (unsigned long)sp.chunks, (unsigned long)(sp.bytes / 1024));
    if (view.wifi.up) lv_label_set_text_fmt(device_values[5], "%s \xC2\xB7 %d dBm", view.wifi.connected, view.wifi.rssi);
    else lv_label_set_text(device_values[5], "Not connected");
    lv_label_set_text(device_values[6], ble_ready() ? "Linked to a hub" : "Not linked");
    lv_label_set_text_fmt(device_values[7], "%d.%02d V \xC2\xB7 %d mA", last.voltage_mv / 1000, last.voltage_mv % 1000 / 10, last.current_ma);
    if (last.climate_ok) lv_label_set_text_fmt(device_values[8], "%d.%d \xC2\xB0" "C \xC2\xB7 %d%% humidity", last.room_c10 / 10, last.room_c10 % 10, last.humidity);
    else lv_label_set_text(device_values[8], "-");
}

static void build_stats(void)
{
    lv_obj_t *s = screen(&v_stats, "Stats", &v_launcher);
    stats_pages[0] = plain(s);
    lv_obj_set_size(stats_pages[0], UI_W, PAGER_Y);
    stats_big = label(stats_pages[0], "", &title_40);
    lv_obj_set_pos(stats_big, 24, TOP - 8);
    stats_sub = label(stats_pages[0], "", &text_18);
    lv_obj_set_pos(stats_sub, 24, TOP + 44);
    section(stats_pages[0], "BATTERY \xC2\xB7 LAST 24 H", TOP + 88);
    lv_obj_t *chart = plain(stats_pages[0]);
    lv_obj_set_size(chart, 440, 180);
    lv_obj_set_pos(chart, 24, TOP + 120);
    static lv_point_precise_t grid[3][2];
    for (int i = 0; i < 3; i++) {
        int y = 8 + i * 67;
        grid[i][0] = (lv_point_precise_t){40, y};
        grid[i][1] = (lv_point_precise_t){432, y};
        lv_obj_t *l = lv_line_create(chart);
        lv_line_set_points(l, grid[i], 2);
        lv_obj_set_style_line_width(l, 2, 0);
        lv_obj_set_style_line_dash_width(l, 2, 0);
        lv_obj_set_style_line_dash_gap(l, 8, 0);
        lv_obj_set_pos(label(chart, i == 0 ? "100%" : i == 1 ? "50%" : "0%", &text_16), 0, y - 8);
    }
    chart_line = lv_line_create(chart);
    lv_obj_set_style_line_width(chart_line, 4, 0);
    lv_obj_set_style_line_rounded(chart_line, true, 0);
    lv_obj_set_pos(label(chart, "24 h ago", &text_16), 40, 152);
    lv_obj_align(label(chart, "now", &text_16), LV_ALIGN_TOP_RIGHT, -8, 152);
    stats_pages[1] = plain(s);
    lv_obj_set_size(stats_pages[1], UI_W, PAGER_Y);
    const char *names[9] = {"Firmware", "CPU", "RAM", "PSRAM", "Storage", "Wi-Fi", "Bluetooth", "Battery", "Room"};
    for (int i = 0; i < 9; i++) device_values[i] = row_value(row(stats_pages[1], TOP - 8 + i * 60, names[i]), -24);
    pager_init(&v_stats, &stats_pager, render_stats);
    v_stats.on_show = render_stats;
}

static void mic_clicked(lv_event_t *e) { actions.mute_toggle(); }

static void show_settings(void)
{
    lv_label_set_text_fmt(rec_value, "%d kbps", current.bitrate_kbps);
    for (int i = 0; i < 3; i++)
        if (QUALITIES[i] == current.bitrate_kbps) seg_set(&quality, i);
    switch_set(&flip_switch, current.flip_stop);
    switch_set(&beep_switch, current.beep);
    switch_set(&mic_switch, recording);
    for (int i = 0; i < SLOT_COUNT; i++) lv_label_set_text(slot_values[i], ACTION_NAMES[current.buttons[i]]);
    for (int i = 0; i < ACT_COUNT; i++) set_hidden(choose_checks[i], current.buttons[choose_slot] != i);
}

static void quality_clicked(lv_event_t *e)
{
    current.bitrate_kbps = QUALITIES[(int)(intptr_t)lv_event_get_user_data(e)];
    show_settings();
    actions.settings_changed(&current);
}

static void flip_clicked(lv_event_t *e)
{
    current.flip_stop = !current.flip_stop;
    show_settings();
    actions.settings_changed(&current);
}

static void show_sd(void);

static void build_settings(void)
{
    lv_obj_t *s = screen(&v_settings, "Settings", &v_launcher);
    v_settings.on_show = show_sd;
    rec_value = nav_row(s, TOP - 8, ICON_MIC, "Recording", &v_rec);
    wifi_value = nav_row(s, TOP - 8 + ROW_H, ICON_WIFI, "Wi-Fi", &v_wifi);
    hub_value = nav_row(s, TOP - 8 + 2 * ROW_H, ICON_DEVICE, "Hub", &v_hub);
    weather_value = nav_row(s, TOP - 8 + 3 * ROW_H, ICON_CLOUD, "Weather", &v_wsettings);
    buttons_value = nav_row(s, TOP - 8 + 4 * ROW_H, ICON_HAND, "Buttons", &v_buttons);
    sd_value = nav_row(s, TOP - 8 + 5 * ROW_H, ICON_DEVICE, "SD card", &v_sd);
    lv_label_set_text_fmt(nav_row(s, TOP - 8 + 6 * ROW_H, ICON_DEVICE, "Device", &v_device), "v%s", esp_app_get_description()->version);
}

static void build_rec_settings(void)
{
    lv_obj_t *s = screen(&v_rec, "Recording", &v_settings);
    lv_obj_t *mic = row(s, TOP - 8, "Microphone");
    lv_obj_add_flag(mic, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(mic, mic_clicked, LV_EVENT_CLICKED, NULL);
    switch_create(&mic_switch, mic);
    section(s, "QUALITY", TOP + 68);
    static const char *const kbps[3] = {"8 kbps", "12 kbps", "16 kbps"};
    seg_create(&quality, s, TOP + 96, kbps, 3, quality_clicked);
    lv_obj_t *flip = row(s, TOP + 176, "Stop when face down");
    lv_obj_add_flag(flip, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(flip, flip_clicked, LV_EVENT_CLICKED, NULL);
    switch_create(&flip_switch, flip);
}

static void forget_clicked(lv_event_t *e) { actions.forget_wifi(net_ssids[(int)(intptr_t)lv_event_get_user_data(e)]); }

static void build_wifi(void)
{
    lv_obj_t *s = screen(&v_wifi, "Wi-Fi", &v_settings);
    section(s, "SAVED NETWORKS", TOP);
    net_empty = label(s, "No saved networks", &text_20);
    lv_obj_set_pos(net_empty, 24, TOP + 40);
    for (int i = 0; i < WIFI_MAX_PROFILES; i++) {
        lv_obj_t *r = row(s, TOP + 28 + i * ROW_H, "");
        net_names[i] = lv_obj_get_child(r, 0);
        lv_obj_align(net_names[i], LV_ALIGN_TOP_LEFT, 24, 8);
        net_states[i] = label(r, "", &text_16);
        lv_obj_align(net_states[i], LV_ALIGN_TOP_LEFT, 24, 36);
        lv_obj_t *f = plain(r);
        lv_obj_set_size(f, 120, 52);
        lv_obj_align(f, LV_ALIGN_RIGHT_MID, -24, 0);
        lv_obj_set_style_radius(f, 26, 0);
        lv_obj_set_style_border_width(f, 3, 0);
        lv_obj_add_flag(f, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_event_cb(f, forget_clicked, LV_EVENT_CLICKED, (void *)(intptr_t)i);
        lv_obj_center(label(f, "Forget", &text_18));
        net_rows[i] = r;
    }
    lv_obj_set_pos(label(s, "Add a network in the phone app: Sticky \xE2\x80\xBA Wi-Fi.", &body_18), 24, TOP + 28 + WIFI_MAX_PROFILES * ROW_H + 16);
}

static void test_clicked(lv_event_t *e)
{
    lv_label_set_text(hub_status, "Testing...");
    actions.test_hub();
}

static void build_hub(void)
{
    lv_obj_t *s = screen(&v_hub, "Hub", &v_settings);
    hub_name = row_value(row(s, TOP - 8, "Name"), -24);
    hub_status = row_value(row(s, TOP - 8 + ROW_H, "Status"), -24);
    button(s, "Test connection", TOP + 140, false, test_clicked);
}

static void slot_clicked(lv_event_t *e)
{
    choose_slot = (int)(intptr_t)lv_event_get_user_data(e);
    lv_label_set_text(choose_title, SLOT_NAMES[choose_slot]);
    show_settings();
    show_view(&v_choose);
}

static void choose_clicked(lv_event_t *e)
{
    current.buttons[choose_slot] = (int)(intptr_t)lv_event_get_user_data(e);
    show_settings();
    actions.settings_changed(&current);
    show_view(&v_buttons);
}

static void build_buttons(void)
{
    lv_obj_t *s = screen(&v_buttons, "Buttons", &v_settings);
    const struct { const char *sec; const char *press; int slot; } rows[] = {
        {"POWER", "Press", SLOT_POWER_PRESS}, {NULL, "Double press", SLOT_POWER_DOUBLE}, {NULL, "Hold 10 s", -1},
        {"UP", "Press", SLOT_UP_PRESS},       {NULL, "Hold", SLOT_UP_HOLD},
        {"DOWN", "Press", SLOT_DOWN_PRESS},   {NULL, "Hold", SLOT_DOWN_HOLD},
        {"BACK", "Double tap", SLOT_BACK_TAP},
    };
    int y = TOP - 16;
    for (int i = 0; i < 8; i++) {
        if (rows[i].sec) {
            section(s, rows[i].sec, y + 8);
            y += 32;
        }
        lv_obj_t *r = row(s, y, rows[i].press);
        lv_obj_t *v = row_value(r, rows[i].slot < 0 ? -24 : -56);
        if (rows[i].slot < 0) {
            lv_label_set_text(v, "Power off");
        } else {
            lv_obj_add_flag(r, LV_OBJ_FLAG_CLICKABLE);
            lv_obj_add_event_cb(r, slot_clicked, LV_EVENT_CLICKED, (void *)(intptr_t)rows[i].slot);
            lv_obj_align(label(r, ICON_NEXT, &icons_34), LV_ALIGN_RIGHT_MID, -14, 0);
            slot_values[rows[i].slot] = v;
        }
        y += ROW_H;
    }
    lv_obj_t *mr = row(s, y + 8, "Button map");
    lv_obj_add_flag(mr, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(mr, go_cb, LV_EVENT_CLICKED, &v_map);
    lv_obj_align(label(mr, ICON_NEXT, &icons_34), LV_ALIGN_RIGHT_MID, -14, 0);
    lv_obj_t *c = screen(&v_choose, "Buttons", &v_buttons);
    choose_title = section(c, "", TOP);
    for (int i = 0; i < ACT_COUNT; i++) {
        lv_obj_t *r = row(c, TOP + 28 + i * ROW_H, ACTION_NAMES[i]);
        lv_obj_add_flag(r, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_event_cb(r, choose_clicked, LV_EVENT_CLICKED, (void *)(intptr_t)i);
        choose_checks[i] = label(r, ICON_CHECK, &icons_34);
        lv_obj_align(choose_checks[i], LV_ALIGN_RIGHT_MID, -24, 0);
    }
}


static lv_obj_t *map_labels[4];

static void map_line(lv_obj_t *parent, lv_point_precise_t *pts, int n)
{
    lv_obj_t *l = lv_line_create(parent);
    lv_line_set_points(l, pts, n);
    lv_obj_set_style_line_width(l, 2, 0);
}

static const char *act_lower(int slot, char *buf, size_t n)
{
    strlcpy(buf, ACTION_NAMES[current.buttons[slot]], n);
    for (char *c = buf; *c; c++) *c = tolower((unsigned char)*c);
    return buf;
}

static void show_map(void)
{
    char a[24], b[24];
    lv_label_set_text_fmt(map_labels[0], "press: %s\ndouble: %s\nhold 10 s: power off", act_lower(SLOT_POWER_PRESS, a, sizeof a),
                          act_lower(SLOT_POWER_DOUBLE, b, sizeof b));
    lv_label_set_text_fmt(map_labels[1], "press: %s\nhold: %s", act_lower(SLOT_UP_PRESS, a, sizeof a), act_lower(SLOT_UP_HOLD, b, sizeof b));
    lv_label_set_text_fmt(map_labels[2], "press: %s\nhold: %s", act_lower(SLOT_DOWN_PRESS, a, sizeof a), act_lower(SLOT_DOWN_HOLD, b, sizeof b));
    lv_label_set_text_fmt(map_labels[3], "Double tap on the back: %s", act_lower(SLOT_BACK_TAP, a, sizeof a));
}

static void build_map(void)
{
    lv_obj_t *s = screen(&v_map, "Button map", &v_buttons);
    v_map.on_show = show_map;
    lv_obj_t *body = plain(s);
    lv_obj_set_size(body, 170, 300);
    lv_obj_set_pos(body, 40, 150);
    lv_obj_set_style_radius(body, 24, 0);
    lv_obj_set_style_border_width(body, 4, 0);
    lv_obj_t *glass = plain(body);
    lv_obj_set_size(glass, 138, 268);
    lv_obj_center(glass);
    lv_obj_set_style_radius(glass, 10, 0);
    lv_obj_set_style_border_width(glass, 2, 0);
    static const struct { int y, h, ty; const char *name; } KEYS[3] = {{185, 22, 96, "POWER"}, {245, 34, 236, "UP"}, {287, 34, 352, "DOWN"}};
    static lv_point_precise_t pts[3][4];
    for (int i = 0; i < 3; i++) {
        lv_obj_t *k = plain(s);
        lv_obj_set_size(k, 8, KEYS[i].h);
        lv_obj_set_pos(k, 210, KEYS[i].y);
        lv_obj_set_style_radius(k, 4, 0);
        lv_obj_set_style_bg_opa(k, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(k, lv_color_black(), 0);
        int mid = KEYS[i].y + KEYS[i].h / 2, ty = KEYS[i].ty + 10;
        pts[i][0] = (lv_point_precise_t){220, mid};
        pts[i][1] = (lv_point_precise_t){234, mid};
        pts[i][2] = (lv_point_precise_t){234, ty};
        pts[i][3] = (lv_point_precise_t){244, ty};
        map_line(s, pts[i], 4);
        lv_obj_set_pos(label(s, KEYS[i].name, &label_16), 250, KEYS[i].ty);
        map_labels[i] = label(s, "", &body_18);
        lv_obj_set_width(map_labels[i], UI_W - 258);
        lv_obj_set_pos(map_labels[i], 250, KEYS[i].ty + 24);
    }
    map_labels[3] = label(s, "", &body_18);
    lv_obj_set_width(map_labels[3], UI_W - 48);
    lv_label_set_long_mode(map_labels[3], LV_LABEL_LONG_WRAP);
    lv_obj_set_pos(map_labels[3], 24, 490);
    lv_obj_t *wake = label(s, "Any key wakes the Sticky from sleep.", &body_18);
    lv_obj_set_pos(wake, 24, 560);
}

static lv_obj_t *sd_rows[5], *sd_format, *sd_status;
static uint32_t sd_confirm_until;

static void size_text(char *buf, size_t n, uint64_t bytes)
{
    if (bytes >= (1ULL << 30)) snprintf(buf, n, "%llu.%llu GB", bytes >> 30, ((bytes >> 20) % 1024) * 10 / 1024);
    else snprintf(buf, n, "%llu MB", bytes >> 20);
}

static void show_sd(void)
{
    sdcard_info_t i;
    sdcard_info(&i);
    char total[16], used[16], free_[16];
    size_text(total, sizeof total, i.total);
    size_text(used, sizeof used, i.total - i.free);
    size_text(free_, sizeof free_, i.free);
    lv_label_set_text(sd_rows[0], !i.present ? "No card" : i.mounted ? i.name : "Not readable");
    lv_label_set_text(sd_rows[1], i.mounted ? total : "-");
    lv_label_set_text(sd_rows[2], i.mounted ? used : "-");
    lv_label_set_text(sd_rows[3], i.mounted ? free_ : "-");
    if (i.mounted) lv_label_set_text_fmt(sd_rows[4], "%d MHz \xC2\xB7 FAT", i.speed_khz / 1000);
    else lv_label_set_text(sd_rows[4], "-");
    set_hidden(sd_format, !i.present);
    if (i.mounted) lv_label_set_text_fmt(sd_value, "%s \xC2\xB7 %s free", total, free_);
    else lv_label_set_text(sd_value, i.present ? "Not readable" : "No card");
}

static void format_clicked(lv_event_t *e)
{
    lv_obj_t *text = lv_obj_get_child(sd_format, 0);
    if (tick_ms() > sd_confirm_until) {
        sd_confirm_until = tick_ms() + 5000;
        lv_label_set_text(text, "Tap again to erase everything");
        set_inverted(sd_format, true);
        return;
    }
    sd_confirm_until = 0;
    lv_label_set_text(sd_status, "Formatting...");
    lv_refr_now(NULL);
    bool ok = sdcard_format();
    lv_label_set_text(sd_status, ok ? "Formatted. The card is empty." : "Format failed. Check the card.");
    lv_label_set_text(text, "Format card");
    set_inverted(sd_format, false);
    show_sd();
}

static void build_sd(void)
{
    lv_obj_t *s = screen(&v_sd, "SD card", &v_settings);
    v_sd.on_show = show_sd;
    const char *names[5] = {"Card", "Size", "Used", "Free", "Speed"};
    for (int i = 0; i < 5; i++) sd_rows[i] = row_value(row(s, TOP - 8 + i * ROW_H, names[i]), -24);
    sd_status = label(s, "", &body_18);
    lv_obj_set_pos(sd_status, 24, UI_H - 130);
    sd_format = button(s, "Format card", UI_H - 88, false, format_clicked);
}

static void update_clicked(lv_event_t *e) { actions.install_update(); }
static void restart_clicked(lv_event_t *e) { actions.restart(); }
static void power_off_clicked(lv_event_t *e) { actions.power_off(); }

static void beep_clicked(lv_event_t *e)
{
    current.beep = !current.beep;
    show_settings();
    actions.settings_changed(&current);
}

static void build_device(void)
{
    lv_obj_t *s = screen(&v_device, "Device", &v_settings);
    lv_label_set_text(row_value(row(s, TOP - 8, "Firmware"), -24), esp_app_get_description()->version);
    lv_obj_t *br = row(s, TOP + 56, "Sounds");
    lv_obj_add_flag(br, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(br, beep_clicked, LV_EVENT_CLICKED, NULL);
    switch_create(&beep_switch, br);
    update_button = button(s, "", UI_H - 248, true, update_clicked);
    lv_obj_add_flag(update_button, LV_OBJ_FLAG_HIDDEN);
    button(s, "Restart", UI_H - 168, false, restart_clicked);
    button(s, "Shut down", UI_H - 88, false, power_off_clicked);
}

static void show_update(const net_state_t *n)
{
    lv_obj_t *text = lv_obj_get_child(update_button, 0);
    if (n->updating) lv_label_set_text_fmt(text, "Updating %d%%", n->update_progress);
    else if (n->update_version[0]) lv_label_set_text_fmt(text, "Update to v%s", n->update_version);
    set_hidden(update_button, !n->updating && !n->update_version[0]);
}

static void build_pair(void)
{
    lv_obj_t *s = screen(&v_pair, "Pairing", NULL);
    lv_obj_t *hint = label(s, "Enter this code on the phone.", &text_20);
    lv_obj_set_pos(hint, 24, TOP);
    lv_obj_t *box = plain(s);
    lv_obj_set_size(box, UI_W - 40, 160);
    lv_obj_align(box, LV_ALIGN_CENTER, 0, -40);
    lv_obj_set_style_border_width(box, 3, 0);
    lv_obj_set_style_radius(box, 24, 0);
    pair_code = label(box, "", &title_40);
    lv_obj_center(pair_code);
}

void ui_show_passkey(int passkey)
{
    static view_t *back;
    if (passkey >= 0) {
        splash_on = false;
        lv_label_set_text_fmt(pair_code, "%03d %03d", passkey / 1000, passkey % 1000);
        if (current_view != &v_pair) back = current_view ? current_view : &v_launcher;
        show_view(&v_pair);
    } else if (current_view == &v_pair) {
        show_view(back);
    }
}

void ui_show_off(void)
{
    splash_on = true;
    force_full = true;
    show_splash(SPLASH_OFF);
}

static void set_status_wifi(const wifi_status_t *w)
{
    const char *icon = sleeping ? ICON_MOON
                       : !w->up  ? ICON_WIFI_OFF
                                 : WIFI_LEVELS[w->rssi >= -55 ? 3 : w->rssi >= -67 ? 2 : w->rssi >= -78 ? 1 : 0];
    if (strcmp(lv_label_get_text(status_wifi), icon)) lv_label_set_text(status_wifi, icon);
}

static void set_rec_tile(void)
{
    bool on = recording && !sleeping;
    set_inverted(rec_tile, on);
    lv_obj_set_style_text_color(rec_tile_icon, on ? lv_color_white() : lv_color_black(), 0);
    lv_label_set_text(rec_tile_icon, on ? ICON_MIC : ICON_MIC_OFF);
}

void ui_show_sleep(void)
{
    sleeping = true;
    set_status_wifi(&view.wifi);
    set_rec_tile();
    lv_refr_now(NULL);
}

void ui_boot_step(int step)
{
    if (!splash_on || step <= boot_shown || step > SPLASH_BOOT_STEPS) return;
    boot_shown = step;
    show_splash(step - 1);
    if (step == SPLASH_BOOT_STEPS && !boot_ready_ms) boot_ready_ms = tick_ms();
}

static void end_splash(void)
{
    splash_on = false;
    force_full = true;
    show_view(&v_launcher);
    lv_obj_invalidate(lv_screen_active());
}

void ui_init(const ui_actions_t *a)
{
    actions = *a;
    rec_since_us = esp_timer_get_time();
    lv_init();
    lv_tick_set_cb(tick_ms);
    panel_fb = heap_caps_malloc(EPD_FB_SIZE, MALLOC_CAP_SPIRAM);
    shown_fb = heap_caps_malloc(EPD_FB_SIZE, MALLOC_CAP_SPIRAM);
    size_t buf_size = UI_W * UI_H;
    uint8_t *buf = heap_caps_malloc(buf_size, MALLOC_CAP_SPIRAM);
    lv_display_t *disp = lv_display_create(UI_W, UI_H);
    lv_display_set_color_format(disp, LV_COLOR_FORMAT_L8);
    lv_display_set_antialiasing(disp, false);
    lv_display_set_buffers(disp, buf, NULL, buf_size, LV_DISPLAY_RENDER_MODE_FULL);
    lv_display_set_flush_cb(disp, flush);
    lv_indev_t *indev = lv_indev_create();
    lv_indev_set_type(indev, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(indev, touch_cb);

    build_status();
    build_launcher();
    build_recorder();
    build_transcripts();
    build_todo();
    build_clock();
    build_weather();
    build_weather_settings();
    build_stats();
    build_settings();
    build_rec_settings();
    build_wifi();
    build_hub();
    build_buttons();
    build_device();
    build_map();
    build_sd();
    build_pair();
    screen(&v_off, NULL, NULL);
    ui_set_recording(true);
    show_view(&v_launcher);
    ui_boot_step(1);
}

void ui_set_settings(const settings_t *s)
{
    current = *s;
    show_settings();
}

void ui_set_recording(bool rec)
{
    if (rec != recording) rec_since_us = esp_timer_get_time();
    recording = rec;
    set_rec_tile();
    switch_set(&mic_switch, rec);
    show_recorder();
}

static void add_history(int soc)
{
    time_t now = time(NULL);
    if (history_len && now - history_at < HISTORY_S && now >= history_at) return;
    history_at = now;
    if (history_len == HISTORY) memmove(history, history + 1, HISTORY - 1);
    else history_len++;
    history[history_len - 1] = soc;
    if (current_view == &v_stats) render_stats();
}

void ui_set_sensors(const sensors_t *s)
{
    last = *s;
    if (s->gauge_ok) {
        char pct[8];
        snprintf(pct, sizeof pct, "%d%%", s->soc_pct);
        if (strcmp(lv_label_get_text(status_batt), pct)) lv_label_set_text(status_batt, pct);
        add_history(s->soc_pct);
    }
    const char *icon = s->usb ? ICON_BATTERY_CHARGING : BATTERY_LEVELS[LV_CLAMP(0, (s->soc_pct + 12) / 25, 4)];
    if (strcmp(lv_label_get_text(status_batt_icon), icon)) lv_label_set_text(status_batt_icon, icon);
}

void ui_set_net(const net_state_t *n)
{
    view = *n;
    show_update(n);
    const wifi_status_t *w = &n->wifi;
    set_status_wifi(w);
    if (w->up) ui_boot_step(5);
    if (n->hub) ui_boot_step(6);
    lv_label_set_text(wifi_value, w->up ? w->connected : w->count ? "Not connected" : "Not set");
    lv_label_set_text(hub_value, n->hub ? "OK" : "Not found");
    lv_label_set_text(hub_name, n->hub ? n->hub_host : "-");
    lv_label_set_text(hub_status, n->hub ? "OK" : w->up ? "Not found" : "No Wi-Fi");
    set_hidden(net_empty, w->count > 0);
    for (int i = 0; i < WIFI_MAX_PROFILES; i++) {
        set_hidden(net_rows[i], i >= w->count);
        if (i >= w->count) continue;
        strlcpy(net_ssids[i], w->ssid[i], sizeof net_ssids[i]);
        bool here = !strcmp(w->ssid[i], w->connected);
        lv_label_set_text(net_names[i], w->ssid[i]);
        lv_label_set_text(net_states[i], here ? (w->up ? "Connected" : "Connecting") : "Saved");
    }
    set_badge(todo_badge, lv_obj_get_user_data(todo_badge), n->todos_loaded ? n->open_todos : 0);
    set_badge(list_badge, lv_obj_get_user_data(list_badge), n->count);
    render_todos();
    render_transcripts();
    show_weather();
    show_weather_settings();
    if (current_view == &v_clock || current_view == &v_launcher) show_clock();
    if (n->count == 0) return;
    const net_item_t *f = &n->items[0];
    lv_obj_remove_flag(last_box, LV_OBJ_FLAG_HIDDEN);
    lv_label_set_text_fmt(last_title, "%s \xC2\xB7 %s", f->when, f->ready ? f->title : "Live transcript");
    lv_label_set_text(last_text, f->ready ? f->summary : (f->text[0] ? f->text : "Waiting for the first 30 s..."));
}


static char console_cmd[48];
static volatile bool console_pending;

void ui_console(const char *command)
{
    if (console_pending) return;
    strlcpy(console_cmd, command, sizeof console_cmd);
    console_pending = true;
}

static void dump_screen(void)
{
    static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    char line[81];
    esp_log_level_set("*", ESP_LOG_NONE);
    vTaskDelay(pdMS_TO_TICKS(50));
    printf("SHOT BEGIN %d\n", EPD_FB_SIZE);
    for (int i = 0, row = 0; i < EPD_FB_SIZE; row++) {
        if (row % 10 == 0) vTaskDelay(1);
        int n = 0;
        for (int k = 0; k < 20 && i < EPD_FB_SIZE; k++, i += 3) {
            uint32_t v = shown_fb[i] << 16 | (i + 1 < EPD_FB_SIZE ? shown_fb[i + 1] << 8 : 0) | (i + 2 < EPD_FB_SIZE ? shown_fb[i + 2] : 0);
            line[n++] = B64[v >> 18 & 63];
            line[n++] = B64[v >> 12 & 63];
            line[n++] = i + 1 < EPD_FB_SIZE ? B64[v >> 6 & 63] : '=';
            line[n++] = i + 2 < EPD_FB_SIZE ? B64[v & 63] : '=';
        }
        line[n] = 0;
        printf("SHOT:%d:%s\n", row, line);
    }
    printf("SHOT END\n");
    fflush(stdout);
    esp_log_level_set("*", ESP_LOG_INFO);
}

static void run_console(void)
{
    static const struct { const char *name; view_t *v; } VIEWS[] = {
        {"launcher", &v_launcher}, {"recorder", &v_recorder}, {"todo", &v_todo}, {"transcripts", &v_transcripts},
        {"clock", &v_clock}, {"weather", &v_weather}, {"stats", &v_stats}, {"settings", &v_settings}, {"recording", &v_rec},
        {"wifi", &v_wifi}, {"hub", &v_hub}, {"wsettings", &v_wsettings}, {"buttons", &v_buttons}, {"choose", &v_choose},
        {"device", &v_device}, {"map", &v_map}, {"sd", &v_sd},
    };
    static const char *const FACE_NAMES[FACE_COUNT] = {"dial", "digits", "words", "note", "flip"};
    static const char *const STYLE_NAMES[WXS_COUNT] = {"sketch", "big", "journal", "week"};
    console_pending = false;
    if (!strcmp(console_cmd, "shot")) return dump_screen();
    char name[24] = "", arg[16] = "";
    sscanf(console_cmd + 5, "%23s %15s", name, arg);
    for (int i = 0; i < FACE_COUNT; i++)
        if (!strcmp(arg, FACE_NAMES[i])) view.clock_face = i;
    for (int i = 0; i < WXS_COUNT; i++)
        if (!strcmp(arg, STYLE_NAMES[i])) view.weather_style = i;
    for (size_t i = 0; i < sizeof VIEWS / sizeof VIEWS[0]; i++)
        if (!strcmp(name, VIEWS[i].name)) {
            if (VIEWS[i].v->pager && arg[0] >= '1' && arg[0] <= '9') VIEWS[i].v->pager->page = arg[0] - '1';
            show_view(VIEWS[i].v);
            if (VIEWS[i].v->pager) VIEWS[i].v->pager->render();
        }
}

void ui_tick(void)
{
    if (console_pending && !splash_on) run_console();
    if (photo_pending && !splash_on) draw_photo();
    if (sd_confirm_until && tick_ms() > sd_confirm_until) {
        sd_confirm_until = 0;
        lv_label_set_text(lv_obj_get_child(sd_format, 0), "Format card");
        set_inverted(sd_format, false);
    }
    if (toast && toast_until && tick_ms() > toast_until) {
        toast_until = 0;
        lv_obj_add_flag(toast, LV_OBJ_FLAG_HIDDEN);
    }
    lv_timer_handler();
    uint32_t now = tick_ms();
    if (splash_on && boot_shown && ((boot_ready_ms && now - boot_ready_ms > BOOT_READY_HOLD_MS) || now > BOOT_TIMEOUT_MS))
        end_splash();
    if (splash_on) return;
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    int minute = clock_valid() ? tm.tm_hour * 60 + tm.tm_min : -1;
    if (minute != shown_minute && (current_view == &v_launcher || current_view == &v_clock)) show_clock();
    if (current_view == &v_weather && view.version != weather_shown) {
        weather_shown = view.version;
        show_weather();
    }
    static int64_t rec_minute = -1;
    int64_t m = (esp_timer_get_time() - rec_since_us) / 60000000;
    if (current_view == &v_recorder && m != rec_minute) {
        rec_minute = m;
        show_recorder();
    }
}
