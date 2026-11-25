#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "Stk1160Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define STK1160_GCTRL 0x000
#define STK1160_DCTRL 0x100
#define STK1160_DMCTRL 0x106
#define STK1160_SICTL 0x200
#define STK1160_SICTL_CD 0x202
#define STK1160_SICTL_SDA 0x203
#define STK1160_SBUSW_WA 0x204
#define STK1160_SBUSW_WD 0x205
#define STK1160_SBUSR_RA 0x208
#define STK1160_SBUSR_RD 0x209

#define SAA7113_I2C_ADDR 0x4A
#define SAA7113_REG_MODE0 0x02
#define SAA7113_REG_SYNC_CNTL 0x08
#define SAA7113_REG_BRIGHTNESS 0x0A
#define SAA7113_REG_CONTRAST 0x0B
#define SAA7113_REG_SATURATION 0x0C
#define SAA7113_REG_CHROMA_CNTL1 0x0E
#define SAA7113_REG_CHROMA_GAIN 0x0F
#define SAA7113_REG_OUTPUT_CNTL1 0x11
#define SAA7113_REG_STATUS_1E 0x1E
#define SAA7113_REG_STATUS 0x1F

/* Padrões analógicos (alinhados com AnalogVideoStandard.nativeId) */
#define STD_NTSC  0
#define STD_PAL_M 1
#define STD_PAL_N 2
#define STD_PAL   3

#define STK1160_ISO_EP 0x82
#define NUM_ISO_URBS 8
#define PACKETS_PER_URB 32
#define MAX_FRAME_BYTES (720 * 576 * 2)
#define MOTION_DEINTERLACE_THRESHOLD 24

typedef struct {
    uint16_t reg;
    uint16_t val;
} regval_t;

static const uint8_t GCTRL_CHANNELS[4] = {0x98, 0x90, 0x88, 0x80};

static const regval_t STK1160_RESET_REGS[] = {
    {0x002, 0x0078}, {0x00d, 0x0000}, {0x00f, 0x0002},
    {0x018, 0x0010}, {0x019, 0x0000}, {0x01a, 0x0014},
    {0x01b, 0x000e}, {0x01c, 0x0046}, {0x300, 0x0012},
    {0x350, 0x002d}, {0x351, 0x0001}, {0x352, 0x0000},
    {0x353, 0x0000}, {0x300, 0x0080},
    {0x202, 0x000f}, {0x2ff, 0x0000},
    {0x106, 0x0000}, {0x100, 0x0000}, {0x103, 0x0000}
};

/* 525 linhas / 60 Hz (NTSC e PAL-M): 720x480 -> H=1440 (0x05A0), V_start=3, V_end=243 (0x00F3) */
static const regval_t STK1160_STD525_REGS[] = {
    {0x110, 0x0000}, {0x111, 0x0000}, {0x112, 0x0003}, {0x113, 0x0000},
    {0x114, 0x00a0}, {0x115, 0x0005}, {0x116, 0x00f3}, {0x117, 0x0000}
};

/* 625 linhas / 50 Hz (PAL e PAL-N): 720x576 -> H=1440 (0x05A0), V_start=1, V_end=289 (0x0121) */
static const regval_t STK1160_STD625_REGS[] = {
    {0x110, 0x0000}, {0x111, 0x0000}, {0x112, 0x0001}, {0x113, 0x0000},
    {0x114, 0x00a0}, {0x115, 0x0005}, {0x116, 0x0021}, {0x117, 0x0001}
};

/* gm7113c_init registradores 0x01..0x17 */
static const uint8_t GM7113C_INIT_VALS[23] = {
    0x08, 0xc0, 0x33, 0x00, 0x00, 0xe9, 0x0d, 0x98,
    0x01, 0x80, 0x47, 0x40, 0x00, 0x01, 0x2a, 0x00,
    0x0c, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00
};

static pthread_mutex_t g_io_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_surface_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_stream_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_frame_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_luma_lock = PTHREAD_MUTEX_INITIALIZER;

static struct {
    atomic_bool running;
    atomic_bool stalled;
    atomic_bool luma_valid;
    atomic_int_fast64_t last_frame_mono_ms;
    atomic_uint_fast64_t frames_rendered;
    atomic_uint_fast64_t frames_dropped;
    atomic_uint_fast64_t iso_packet_errors;
    int thread_active;
    pthread_t iso_thread;
    int usb_fd;
    int alt_setting;
    int iso_packet_size;
    int width;
    int height;
    int standard_id;
    int auto_detect_freq;
    uint8_t reg_brightness;
    uint8_t reg_contrast;
    uint8_t reg_saturation;
    ANativeWindow *window;
    uint8_t staging_uyvy[MAX_FRAME_BYTES];
    uint8_t last_good_uyvy[MAX_FRAME_BYTES];
    int last_good_w;
    int last_good_h;
    int has_good_frame;
    uint8_t last_luma192[192];
    size_t frame_pos;
    int synced_to_field;
    int current_field;
    int field_corrupted;
    int field0_ready;
    int field1_ready;
    int last_completed_field;
    int same_field_streak;
    int64_t last_drop_log_ms;
} g_ctx = {
    .running = 0,
    .stalled = 0,
    .luma_valid = 0,
    .last_frame_mono_ms = 0,
    .frames_rendered = 0,
    .frames_dropped = 0,
    .iso_packet_errors = 0,
    .thread_active = 0,
    .usb_fd = -1,
    .alt_setting = 5,
    .iso_packet_size = 3072,
    .width = 720,
    .height = 480,
    .standard_id = STD_NTSC,
    .auto_detect_freq = 1,
    .reg_brightness = 0x80,
    .reg_contrast = 0x47,
    .reg_saturation = 0x40,
    .window = NULL,
    .last_good_w = 0,
    .last_good_h = 0,
    .has_good_frame = 0,
    .frame_pos = 0,
    .synced_to_field = 0,
    .current_field = 0,
    .field_corrupted = 0,
    .field0_ready = 0,
    .field1_ready = 0,
    .last_completed_field = -1,
    .same_field_streak = 0,
    .last_drop_log_ms = 0
};

static int64_t mono_time_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000LL + (int64_t) (ts.tv_nsec / 1000000LL);
}

static inline int is_std_60hz(int standard_id) {
    return (standard_id == STD_NTSC || standard_id == STD_PAL_M) ? 1 : 0;
}

static inline uint8_t reg0e_for_standard(int standard_id) {
    switch (standard_id) {
        case STD_PAL_M: return 0x31; /* PAL-M (60 Hz, 3,5756 MHz) */
        case STD_PAL_N: return 0x21; /* PAL-N (50 Hz, 3,5820 MHz) */
        case STD_PAL:   return 0x01; /* PAL BGDHI (50 Hz, 4,43 MHz) */
        case STD_NTSC:
        default:        return 0x01; /* NTSC M (60 Hz, 3,58 MHz) */
    }
}

static inline uint8_t reg08_for_standard(int standard_id, int auto_detect_freq) {
    if (auto_detect_freq) {
        return 0x98; /* AUFD=1 (detecta 50/60 Hz automaticamente), HTC=11 (travamento rápido) */
    }
    return is_std_60hz(standard_id) ? 0x58 : 0x18; /* Manual 60Hz/50Hz, HTC=11, FOET=0 */
}

/* Preenche com preto ITU-R BT.601 UYVY (U=128, Y=16, V=128, Y=16) para nunca gerar verde */
static void fill_uyvy_black(uint8_t *buf, size_t bytes) {
    for (size_t i = 0; i + 3 < bytes; i += 4) {
        buf[i] = 0x80;
        buf[i + 1] = 0x10;
        buf[i + 2] = 0x80;
        buf[i + 3] = 0x10;
    }
}

/* Detecta buffer completamente zerado (U=0, Y=0, V=0 geraria tela verde sólida RGB(0,135,0)) */
static int is_uyvy_buffer_all_zero(const uint8_t *buf, size_t bytes) {
    if (!buf || bytes == 0) return 1;
    size_t step = bytes / 256;
    if (step == 0) step = 1;
    for (size_t i = 0; i < bytes; i += step) {
        if (buf[i] != 0) return 0;
    }
    return buf[bytes - 1] == 0 ? 1 : 0;
}

static int usb_ctrl_read_reg(int fd, uint16_t reg, uint8_t *out_val) {
    uint8_t val = 0;
    struct usbdevfs_ctrltransfer ctrl = {
        .bRequestType = 0xC0,
        .bRequest = 0x00,
        .wValue = 0x0000,
        .wIndex = reg,
        .wLength = 1,
        .timeout = 500,
        .data = &val
    };
    int ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        return -errno;
    }
    *out_val = val;
    return 0;
}

static int usb_ctrl_write_reg(int fd, uint16_t reg, uint16_t val) {
    struct usbdevfs_ctrltransfer ctrl = {
        .bRequestType = 0x40,
        .bRequest = 0x01,
        .wValue = val,
        .wIndex = reg,
        .wLength = 0,
        .timeout = 500,
        .data = NULL
    };
    int ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    return (ret < 0) ? -errno : 0;
}

static int stk1160_write_table(int fd, const regval_t *table, size_t count) {
    for (size_t i = 0; i < count; i++) {
        int rc = usb_ctrl_write_reg(fd, table[i].reg, table[i].val);
        if (rc < 0) return rc;
    }
    return 0;
}

static int saa7113_write_reg(int fd, uint8_t reg, uint8_t val) {
    int rc;
    rc = usb_ctrl_write_reg(fd, STK1160_SICTL_SDA, SAA7113_I2C_ADDR);
    if (rc < 0) return rc;
    rc = usb_ctrl_write_reg(fd, STK1160_SBUSW_WA, reg);
    if (rc < 0) return rc;
    rc = usb_ctrl_write_reg(fd, STK1160_SBUSW_WD, val);
    if (rc < 0) return rc;
    rc = usb_ctrl_write_reg(fd, STK1160_SICTL, 0x01);
    if (rc < 0) return rc;

    for (int i = 0; i < 20; i++) {
        uint8_t status = 0;
        rc = usb_ctrl_read_reg(fd, STK1160_SICTL + 1, &status);
        if (rc < 0) return rc;
        if ((status & 0x04) != 0) return 0;
        usleep(500);
    }
    return -ETIMEDOUT;
}

static int saa7113_read_reg(int fd, uint8_t reg, uint8_t *out_val) {
    int rc;
    rc = usb_ctrl_write_reg(fd, STK1160_SICTL_SDA, SAA7113_I2C_ADDR);
    if (rc < 0) return rc;
    rc = usb_ctrl_write_reg(fd, STK1160_SBUSR_RA, reg);
    if (rc < 0) return rc;
    rc = usb_ctrl_write_reg(fd, STK1160_SICTL, 0x20);
    if (rc < 0) return rc;

    for (int i = 0; i < 20; i++) {
        uint8_t status = 0;
        rc = usb_ctrl_read_reg(fd, STK1160_SICTL + 1, &status);
        if (rc < 0) return rc;
        if ((status & 0x01) != 0) {
            return usb_ctrl_read_reg(fd, STK1160_SBUSR_RD, out_val);
        }
        usleep(500);
    }
    return -ETIMEDOUT;
}

static int apply_image_adjustments_locked(int fd, uint8_t reg0a, uint8_t reg0b, uint8_t reg0c) {
    int rc = saa7113_write_reg(fd, SAA7113_REG_BRIGHTNESS, reg0a);
    if (rc < 0) return rc;
    rc = saa7113_write_reg(fd, SAA7113_REG_CONTRAST, reg0b);
    if (rc < 0) return rc;
    rc = saa7113_write_reg(fd, SAA7113_REG_SATURATION, reg0c);
    if (rc < 0) return rc;
    g_ctx.reg_brightness = reg0a;
    g_ctx.reg_contrast = reg0b;
    g_ctx.reg_saturation = reg0c;
    return 0;
}

static int apply_standard_locked(int fd, int standard_id, int auto_detect_freq, int color_only) {
    int rc = 0;
    const int is_60hz = is_std_60hz(standard_id);
    const uint8_t reg08 = reg08_for_standard(standard_id, auto_detect_freq);
    const uint8_t reg0e = reg0e_for_standard(standard_id);

    if (!color_only) {
        if (is_60hz) {
            rc = stk1160_write_table(fd, STK1160_STD525_REGS,
                                     sizeof(STK1160_STD525_REGS) / sizeof(STK1160_STD525_REGS[0]));
        } else {
            rc = stk1160_write_table(fd, STK1160_STD625_REGS,
                                     sizeof(STK1160_STD625_REGS) / sizeof(STK1160_STD625_REGS[0]));
        }
        if (rc < 0) return rc;
    }

    rc = saa7113_write_reg(fd, SAA7113_REG_SYNC_CNTL, reg08);
    if (rc < 0) return rc;
    rc = saa7113_write_reg(fd, SAA7113_REG_CHROMA_CNTL1, reg0e);
    if (rc < 0) return rc;

    if (!color_only) {
        rc = saa7113_write_reg(fd, SAA7113_REG_CHROMA_GAIN, 0x2A);
        if (rc < 0) return rc;
        rc = saa7113_write_reg(fd, SAA7113_REG_MODE0, 0xC0);
        if (rc < 0) return rc;
        rc = apply_image_adjustments_locked(fd, g_ctx.reg_brightness, g_ctx.reg_contrast, g_ctx.reg_saturation);
        if (rc < 0) return rc;
    }

    g_ctx.standard_id = standard_id;
    g_ctx.auto_detect_freq = auto_detect_freq;
    LOGI("SAA7113 padrão aplicado std=%d auto=%d colorOnly=%d reg08=0x%02X reg0E=0x%02X",
         standard_id, auto_detect_freq, color_only, reg08, reg0e);
    return 0;
}

static inline uint8_t clamp_u8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return (uint8_t) v;
}

static inline int abs_i(int v) {
    return (v < 0) ? -v : v;
}

static void update_luma_grid_from_uyvy(const uint8_t *uyvy, int width, int height) {
    if (width < 16 || height < 12) return;
    pthread_mutex_lock(&g_luma_lock);
    for (int gy = 0; gy < 12; gy++) {
        int sy = (gy * height + height / 2) / 12;
        if (sy >= height) sy = height - 1;
        const uint8_t *row = uyvy + (size_t) sy * (size_t) width * 2;
        for (int gx = 0; gx < 16; gx++) {
            int sx = (gx * width + width / 2) / 16;
            if (sx >= width) sx = width - 1;
            g_ctx.last_luma192[gy * 16 + gx] = row[(size_t) sx * 2 + 1];
        }
    }
    atomic_store(&g_ctx.luma_valid, 1);
    pthread_mutex_unlock(&g_luma_lock);
}

static void duplicate_single_field_in_place(uint8_t *uyvy, int width, int height, int source_field) {
    const size_t stride = (size_t) width * 2;
    if (height < 2) return;
    const int target_start = (source_field == 0) ? 1 : 0;
    for (int y = target_start; y < height; y += 2) {
        const int prev_y = (y > 0) ? (y - 1) : 0;
        const int next_y = (y + 1 < height) ? (y + 1) : (height - 1);
        uint8_t *dst = uyvy + (size_t) y * stride;
        const uint8_t *prev = uyvy + (size_t) prev_y * stride;
        const uint8_t *next = uyvy + (size_t) next_y * stride;
        if (prev_y == y) {
            memcpy(dst, next, stride);
        } else if (next_y == y) {
            memcpy(dst, prev, stride);
        } else {
            for (size_t i = 0; i < stride; i++) {
                dst[i] = (uint8_t) (((int) prev[i] + (int) next[i] + 1) >> 1);
            }
        }
    }
}

/*
 * Converte UYVY (Cb, Y0, Cr, Y1) para RGBA_8888 com desentrelaçamento adaptativo
 * (bob interpolado quando há movimento entre campos, filtro vertical 1-2-1 em áreas estáticas).
 */
static void render_uyvy_frame_to_window(const uint8_t *uyvy, int width, int height) {
    const size_t frame_bytes = (size_t) width * (size_t) height * 2;
    if (is_uyvy_buffer_all_zero(uyvy, frame_bytes)) {
        return;
    }

    update_luma_grid_from_uyvy(uyvy, width, height);

    pthread_mutex_lock(&g_surface_lock);
    ANativeWindow *win = g_ctx.window;
    if (!win) {
        pthread_mutex_unlock(&g_surface_lock);
        return;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, NULL) == 0) {
        if (buf.width != width || buf.height != height) {
            ANativeWindow_unlockAndPost(win);
            ANativeWindow_setBuffersGeometry(win, width, height, WINDOW_FORMAT_RGBA_8888);
            if (ANativeWindow_lock(win, &buf, NULL) != 0) {
                pthread_mutex_unlock(&g_surface_lock);
                return;
            }
        }

        const int src_stride = width * 2;
        const int copy_w = (width < buf.width) ? width : buf.width;
        const int copy_h = (height < buf.height) ? height : buf.height;

        for (int y = 0; y < copy_h; y++) {
            const uint8_t *row_curr = uyvy + (size_t) y * (size_t) src_stride;
            uint32_t *dst = ((uint32_t *) buf.bits) + (size_t) y * (size_t) buf.stride;

            if ((y & 1) == 0 || copy_h < 2) {
                /* Linhas pares (campo 0) preservadas como âncora sem tremor vertical */
                const uint8_t *src = row_curr;
                for (int x = 0; x + 1 < copy_w; x += 2) {
                    int u = (int) src[0] - 128;
                    int y0 = (int) src[1] - 16;
                    int v = (int) src[2] - 128;
                    int y1 = (int) src[3] - 16;
                    src += 4;

                    int c0 = 298 * y0;
                    int c1 = 298 * y1;
                    int r_uv = 409 * v + 128;
                    int g_uv = -100 * u - 208 * v + 128;
                    int b_uv = 516 * u + 128;

                    uint8_t r0 = clamp_u8((c0 + r_uv) >> 8);
                    uint8_t g0 = clamp_u8((c0 + g_uv) >> 8);
                    uint8_t b0 = clamp_u8((c0 + b_uv) >> 8);

                    uint8_t r1 = clamp_u8((c1 + r_uv) >> 8);
                    uint8_t g1 = clamp_u8((c1 + g_uv) >> 8);
                    uint8_t b1 = clamp_u8((c1 + b_uv) >> 8);

                    dst[x] = 0xFF000000U | ((uint32_t) b0 << 16) | ((uint32_t) g0 << 8) | (uint32_t) r0;
                    dst[x + 1] = 0xFF000000U | ((uint32_t) b1 << 16) | ((uint32_t) g1 << 8) | (uint32_t) r1;
                }
            } else {
                /* Linhas ímpares (campo 1): bob em movimento / blend 1-2-1 em áreas estáticas */
                const uint8_t *row_prev = uyvy + (size_t) (y - 1) * (size_t) src_stride;
                const int next_y = (y + 1 < height) ? (y + 1) : (height - 1);
                const uint8_t *row_next = uyvy + (size_t) next_y * (size_t) src_stride;

                for (int x = 0; x + 1 < copy_w; x += 2) {
                    const size_t off = (size_t) x * 2;
                    int y0_p = row_prev[off + 1];
                    int y0_c = row_curr[off + 1];
                    int y0_n = row_next[off + 1];
                    int y1_p = row_prev[off + 3];
                    int y1_c = row_curr[off + 3];
                    int y1_n = row_next[off + 3];

                    int y0_bob = (y0_p + y0_n + 1) >> 1;
                    int y1_bob = (y1_p + y1_n + 1) >> 1;
                    int d0 = abs_i(y0_c - y0_bob);
                    int d1 = abs_i(y1_c - y1_bob);

                    int u_val, y0_val, v_val, y1_val;
                    if (d0 > MOTION_DEINTERLACE_THRESHOLD || d1 > MOTION_DEINTERLACE_THRESHOLD) {
                        u_val = ((int) row_prev[off] + (int) row_next[off] + 1) >> 1;
                        y0_val = y0_bob;
                        v_val = ((int) row_prev[off + 2] + (int) row_next[off + 2] + 1) >> 1;
                        y1_val = y1_bob;
                    } else {
                        u_val = ((int) row_prev[off] + ((int) row_curr[off] << 1) + (int) row_next[off] + 2) >> 2;
                        y0_val = (y0_p + (y0_c << 1) + y0_n + 2) >> 2;
                        v_val = ((int) row_prev[off + 2] + ((int) row_curr[off + 2] << 1) + (int) row_next[off + 2] + 2) >> 2;
                        y1_val = (y1_p + (y1_c << 1) + y1_n + 2) >> 2;
                    }

                    int u = u_val - 128;
                    int y0 = y0_val - 16;
                    int v = v_val - 128;
                    int y1 = y1_val - 16;

                    int c0 = 298 * y0;
                    int c1 = 298 * y1;
                    int r_uv = 409 * v + 128;
                    int g_uv = -100 * u - 208 * v + 128;
                    int b_uv = 516 * u + 128;

                    uint8_t r0 = clamp_u8((c0 + r_uv) >> 8);
                    uint8_t g0 = clamp_u8((c0 + g_uv) >> 8);
                    uint8_t b0 = clamp_u8((c0 + b_uv) >> 8);

                    uint8_t r1 = clamp_u8((c1 + r_uv) >> 8);
                    uint8_t g1 = clamp_u8((c1 + g_uv) >> 8);
                    uint8_t b1 = clamp_u8((c1 + b_uv) >> 8);

                    dst[x] = 0xFF000000U | ((uint32_t) b0 << 16) | ((uint32_t) g0 << 8) | (uint32_t) r0;
                    dst[x + 1] = 0xFF000000U | ((uint32_t) b1 << 16) | ((uint32_t) g1 << 8) | (uint32_t) r1;
                }
            }
        }
        ANativeWindow_unlockAndPost(win);
    }
    pthread_mutex_unlock(&g_surface_lock);
}

static void process_stk1160_packet(const uint8_t *p, int len, int stream_w, int stream_h) {
    if (len < 4) return;

    const uint8_t hdr = p[0];
    const int is_new_field = (hdr & 0x80) ? 1 : 0;
    const int new_field = (hdr & 0x40) ? 1 : 0; /* 0 = linhas pares (0,2,4...); 1 = linhas ímpares (1,3,5...) */
    const int hdr_bytes = is_new_field ? 8 : 4; /* Pacotes de início de campo no STK1160 têm cabeçalho de 8 bytes */

    const size_t width = (size_t) stream_w;
    const size_t height = (size_t) stream_h;
    const size_t bytesperline = width * 2;
    const size_t field_lines = height / 2;
    const size_t field_bytes = bytesperline * field_lines;
    const size_t frame_bytes = bytesperline * height;
    const size_t min_valid_field_bytes = (field_bytes * 90) / 100;

    if (is_new_field) {
        if (g_ctx.synced_to_field) {
            const int field_valid = (!g_ctx.field_corrupted && g_ctx.frame_pos >= min_valid_field_bytes) ? 1 : 0;
            if (field_valid) {
                if (g_ctx.current_field == 0) {
                    g_ctx.field0_ready = 1;
                } else {
                    g_ctx.field1_ready = 1;
                }

                if (g_ctx.current_field == g_ctx.last_completed_field) {
                    g_ctx.same_field_streak++;
                } else {
                    g_ctx.same_field_streak = 1;
                }
                g_ctx.last_completed_field = g_ctx.current_field;

                int should_publish = 0;
                if (g_ctx.field0_ready && g_ctx.field1_ready) {
                    should_publish = 1;
                } else if (g_ctx.same_field_streak >= 2) {
                    /* Fallback para câmeras não-entrelaçadas (campo único): linha dupla / bob */
                    duplicate_single_field_in_place(g_ctx.staging_uyvy, stream_w, stream_h, g_ctx.current_field);
                    should_publish = 1;
                }

                if (should_publish) {
                    if (!is_uyvy_buffer_all_zero(g_ctx.staging_uyvy, frame_bytes)) {
                        pthread_mutex_lock(&g_frame_lock);
                        memcpy(g_ctx.last_good_uyvy, g_ctx.staging_uyvy, frame_bytes);
                        g_ctx.last_good_w = stream_w;
                        g_ctx.last_good_h = stream_h;
                        g_ctx.has_good_frame = 1;
                        pthread_mutex_unlock(&g_frame_lock);

                        atomic_store(&g_ctx.last_frame_mono_ms, mono_time_ms());
                        atomic_fetch_add(&g_ctx.frames_rendered, 1);
                        render_uyvy_frame_to_window(g_ctx.last_good_uyvy, stream_w, stream_h);
                    } else {
                        atomic_fetch_add(&g_ctx.frames_dropped, 1);
                    }
                    g_ctx.field0_ready = 0;
                    g_ctx.field1_ready = 0;
                }
            } else {
                uint64_t drops = atomic_fetch_add(&g_ctx.frames_dropped, 1) + 1;
                if (g_ctx.current_field == 0) g_ctx.field0_ready = 0;
                else g_ctx.field1_ready = 0;

                int64_t now = mono_time_ms();
                if (now - g_ctx.last_drop_log_ms > 2000) {
                    g_ctx.last_drop_log_ms = now;
                    LOGW("Campo descartado (field=%d bytes=%zu/%zu corrupted=%d totalDrops=%llu)",
                         g_ctx.current_field, g_ctx.frame_pos, field_bytes, g_ctx.field_corrupted,
                         (unsigned long long) drops);
                }
            }
        }

        g_ctx.synced_to_field = 1;
        g_ctx.current_field = new_field;
        g_ctx.frame_pos = 0;
        g_ctx.field_corrupted = 0;
    } else {
        if (!g_ctx.synced_to_field) {
            return;
        }
        if (new_field != g_ctx.current_field) {
            g_ctx.field_corrupted = 1;
            return;
        }
    }

    if (len <= hdr_bytes) return;
    p += hdr_bytes;
    len -= hdr_bytes;

    /* Comprimento ímpar indica pacote USB truncado que deslocaria U/Y/V/Y e deixaria a imagem verde */
    if ((len & 1) != 0) {
        atomic_fetch_add(&g_ctx.iso_packet_errors, 1);
        g_ctx.field_corrupted = 1;
        return;
    }
    if (g_ctx.field_corrupted) return;

    const size_t field_offset = (g_ctx.current_field == 0) ? 0 : bytesperline;

    while (len > 0) {
        const size_t linesdone = g_ctx.frame_pos / bytesperline;
        const size_t lineoff = g_ctx.frame_pos % bytesperline;
        if (linesdone >= field_lines) {
            break;
        }

        const size_t avail_in_line = bytesperline - lineoff;
        const size_t to_copy = ((size_t) len < avail_in_line) ? (size_t) len : avail_in_line;
        const size_t dst_offset = field_offset + linesdone * bytesperline * 2 + lineoff;

        if (dst_offset + to_copy > frame_bytes) {
            break;
        }

        memcpy(g_ctx.staging_uyvy + dst_offset, p, to_copy);
        p += to_copy;
        len -= (int) to_copy;
        g_ctx.frame_pos += to_copy;
    }
}

static void *iso_stream_thread(void *arg) {
    (void) arg;
    const int fd = g_ctx.usb_fd;
    const int pkt_size = g_ctx.iso_packet_size;
    const int stream_w = g_ctx.width;
    const int stream_h = g_ctx.height;
    const int buf_size = PACKETS_PER_URB * pkt_size;
    const size_t urb_alloc_size =
        sizeof(struct usbdevfs_urb) + PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);

    struct usbdevfs_urb *urbs[NUM_ISO_URBS] = {0};
    int submitted[NUM_ISO_URBS] = {0};
    int consecutive_poll_timeouts = 0;

    for (int i = 0; i < NUM_ISO_URBS; i++) {
        urbs[i] = (struct usbdevfs_urb *) calloc(1, urb_alloc_size);
        uint8_t *buf = (uint8_t *) calloc(1, (size_t) buf_size);
        if (!urbs[i] || !buf) {
            free(buf);
            free(urbs[i]);
            urbs[i] = NULL;
            atomic_store(&g_ctx.running, 0);
            break;
        }
        urbs[i]->type = USBDEVFS_URB_TYPE_ISO;
        urbs[i]->endpoint = STK1160_ISO_EP;
        urbs[i]->flags = USBDEVFS_URB_ISO_ASAP;
        urbs[i]->buffer = buf;
        urbs[i]->buffer_length = buf_size;
        urbs[i]->number_of_packets = PACKETS_PER_URB;
        for (int p = 0; p < PACKETS_PER_URB; p++) {
            urbs[i]->iso_frame_desc[p].length = (unsigned int) pkt_size;
        }
    }

    int submit_ok = 0;
    if (atomic_load(&g_ctx.running)) {
        for (int i = 0; i < NUM_ISO_URBS; i++) {
            if (ioctl(fd, USBDEVFS_SUBMITURB, urbs[i]) == 0) {
                submitted[i] = 1;
                submit_ok++;
            } else {
                LOGE("USBDEVFS_SUBMITURB %d failed: errno=%d (alt=%d pkt=%d)",
                     i, errno, g_ctx.alt_setting, pkt_size);
                break;
            }
        }
    }

    if (submit_ok > 0) {
        pthread_mutex_lock(&g_io_lock);
        usb_ctrl_write_reg(fd, STK1160_DCTRL + 3, 0x00);
        usb_ctrl_write_reg(fd, STK1160_DCTRL, 0xb3);
        pthread_mutex_unlock(&g_io_lock);
        LOGI("STK1160 ISO stream iniciado (%dx%d std=%d alt=%d pkt=%d urbs=%d)",
             stream_w, stream_h, g_ctx.standard_id, g_ctx.alt_setting, pkt_size, submit_ok);
    } else {
        atomic_store(&g_ctx.stalled, 1);
        atomic_store(&g_ctx.running, 0);
    }

    while (atomic_load(&g_ctx.running)) {
        struct pollfd pfd = {
            .fd = fd,
            .events = POLLOUT,
            .revents = 0
        };
        int prc = poll(&pfd, 1, 150);
        if (prc < 0) {
            if (errno == EINTR) continue;
            LOGW("poll error: %d", errno);
            atomic_store(&g_ctx.stalled, 1);
            break;
        }
        if (pfd.revents & (POLLERR | POLLHUP)) {
            LOGW("USB fd desconectado durante poll");
            atomic_store(&g_ctx.stalled, 1);
            break;
        }
        if (prc == 0) {
            consecutive_poll_timeouts++;
            if (consecutive_poll_timeouts >= 15) {
                LOGW("Watchdog ISO: %d timeouts seguidos de poll sem URB; encerrando para reinício",
                     consecutive_poll_timeouts);
                atomic_store(&g_ctx.stalled, 1);
                atomic_store(&g_ctx.running, 0);
                break;
            }
            continue;
        }

        consecutive_poll_timeouts = 0;

        while (atomic_load(&g_ctx.running)) {
            struct usbdevfs_urb *reaped = NULL;
            int rrc = ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped);
            if (rrc < 0 || !reaped) {
                if (errno == EAGAIN || errno == EINTR) break;
                if (errno == ENODEV) {
                    atomic_store(&g_ctx.stalled, 1);
                    atomic_store(&g_ctx.running, 0);
                    break;
                }
                break;
            }

            const uint8_t *base = (const uint8_t *) reaped->buffer;
            for (int p = 0; p < reaped->number_of_packets; p++) {
                const struct usbdevfs_iso_packet_desc *desc = &reaped->iso_frame_desc[p];
                if (desc->status != 0) {
                    atomic_fetch_add(&g_ctx.iso_packet_errors, 1);
                    if (g_ctx.synced_to_field) {
                        g_ctx.field_corrupted = 1;
                    }
                    continue;
                }
                if (desc->actual_length >= 4) {
                    process_stk1160_packet(base + (size_t) p * (size_t) pkt_size,
                                           (int) desc->actual_length,
                                           stream_w,
                                           stream_h);
                }
            }

            if (atomic_load(&g_ctx.running)) {
                reaped->status = 0;
                reaped->actual_length = 0;
                reaped->error_count = 0;
                for (int p = 0; p < reaped->number_of_packets; p++) {
                    reaped->iso_frame_desc[p].actual_length = 0;
                    reaped->iso_frame_desc[p].status = 0;
                }
                if (ioctl(fd, USBDEVFS_SUBMITURB, reaped) < 0) {
                    LOGW("Resubmit URB failed: %d", errno);
                    for (int i = 0; i < NUM_ISO_URBS; i++) {
                        if (urbs[i] == reaped) submitted[i] = 0;
                    }
                    atomic_store(&g_ctx.stalled, 1);
                    atomic_store(&g_ctx.running, 0);
                    break;
                }
            } else {
                for (int i = 0; i < NUM_ISO_URBS; i++) {
                    if (urbs[i] == reaped) submitted[i] = 0;
                }
            }
        }
    }

    atomic_store(&g_ctx.running, 0);

    pthread_mutex_lock(&g_io_lock);
    usb_ctrl_write_reg(fd, STK1160_DCTRL, 0x00);
    usb_ctrl_write_reg(fd, STK1160_DCTRL + 3, 0x00);
    pthread_mutex_unlock(&g_io_lock);

    for (int i = 0; i < NUM_ISO_URBS; i++) {
        if (urbs[i] && submitted[i]) {
            if (ioctl(fd, USBDEVFS_DISCARDURB, urbs[i]) < 0 && errno == ENODEV) {
                submitted[i] = 0;
            }
        }
    }

    int pending = 0;
    for (int i = 0; i < NUM_ISO_URBS; i++) {
        if (urbs[i] && submitted[i]) pending++;
    }

    for (int iter = 0; iter < 12 && pending > 0; iter++) {
        struct pollfd pfd = {
            .fd = fd,
            .events = POLLOUT,
            .revents = 0
        };
        int prc = poll(&pfd, 1, 50);
        if (prc < 0 && errno != EINTR) break;
        if (pfd.revents & (POLLERR | POLLHUP)) break;

        while (pending > 0) {
            struct usbdevfs_urb *reaped = NULL;
            if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped) < 0 || !reaped) {
                if (errno == EAGAIN) break;
                if (errno == EINTR) continue;
                pending = 0;
                break;
            }
            for (int i = 0; i < NUM_ISO_URBS; i++) {
                if (urbs[i] == reaped && submitted[i]) {
                    submitted[i] = 0;
                    pending--;
                }
            }
        }
    }

    struct usbdevfs_setinterface set_zero = {.interface = 0, .altsetting = 0};
    ioctl(fd, USBDEVFS_SETINTERFACE, &set_zero);

    for (int i = 0; i < NUM_ISO_URBS; i++) {
        if (urbs[i] && !submitted[i]) {
            free(urbs[i]->buffer);
            free(urbs[i]);
        }
    }

    LOGI("STK1160 ISO stream parado (rendered=%llu dropped=%llu pktErr=%llu stalled=%d)",
         (unsigned long long) atomic_load(&g_ctx.frames_rendered),
         (unsigned long long) atomic_load(&g_ctx.frames_dropped),
         (unsigned long long) atomic_load(&g_ctx.iso_packet_errors),
         atomic_load(&g_ctx.stalled) ? 1 : 0);
    return NULL;
}

static void stop_streaming_internal(void) {
    pthread_t tid;
    int was_active = 0;

    pthread_mutex_lock(&g_stream_lock);
    atomic_store(&g_ctx.running, 0);
    if (g_ctx.thread_active) {
        tid = g_ctx.iso_thread;
        was_active = 1;
        g_ctx.thread_active = 0;
    }
    pthread_mutex_unlock(&g_stream_lock);

    if (was_active) {
        pthread_join(tid, NULL);
    }
    atomic_store(&g_ctx.luma_valid, 0);
}

JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeInitChip(
    JNIEnv *env, jclass clazz, jint fd, jint channel, jint standardId, jboolean autoDetectFreq,
    jint regBrightness, jint regContrast, jint regSaturation) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    int ch = (channel < 0) ? 0 : (channel > 3 ? 3 : channel);
    int std_id = (standardId < 0 || standardId > 3) ? STD_NTSC : standardId;
    uint8_t r0a = (uint8_t) (regBrightness & 0xFF);
    uint8_t r0b = (uint8_t) (regContrast & 0x7F);
    uint8_t r0c = (uint8_t) (regSaturation & 0x7F);

    pthread_mutex_lock(&g_io_lock);
    g_ctx.reg_brightness = r0a;
    g_ctx.reg_contrast = r0b;
    g_ctx.reg_saturation = r0c;

    int rc = stk1160_write_table(fd, STK1160_RESET_REGS,
                                 sizeof(STK1160_RESET_REGS) / sizeof(STK1160_RESET_REGS[0]));
    if (rc == 0) {
        rc = usb_ctrl_write_reg(fd, STK1160_GCTRL, GCTRL_CHANNELS[ch]);
    }
    if (rc == 0) {
        for (uint8_t i = 0; i < 23; i++) {
            rc = saa7113_write_reg(fd, (uint8_t) (0x01 + i), GM7113C_INIT_VALS[i]);
            if (rc < 0) break;
        }
    }
    if (rc == 0) {
        rc = apply_standard_locked(fd, std_id, autoDetectFreq ? 1 : 0, 0);
    }
    pthread_mutex_unlock(&g_io_lock);

    LOGI("nativeInitChip ch=%d std=%d auto=%d bri=0x%02X con=0x%02X sat=0x%02X rc=%d",
         ch, std_id, autoDetectFreq ? 1 : 0, r0a, r0b, r0c, rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeSelectChannel(
    JNIEnv *env, jclass clazz, jint fd, jint channel) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;
    int ch = (channel < 0) ? 0 : (channel > 3 ? 3 : channel);

    pthread_mutex_lock(&g_io_lock);
    int rc = usb_ctrl_write_reg(fd, STK1160_GCTRL, GCTRL_CHANNELS[ch]);
    pthread_mutex_unlock(&g_io_lock);

    LOGI("nativeSelectChannel ch=%d gctrl=0x%02X rc=%d", ch, GCTRL_CHANNELS[ch], rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeSetStandard(
    JNIEnv *env, jclass clazz, jint fd, jint standardId, jboolean autoDetectFreq, jboolean colorOnly) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    int std_id = (standardId < 0 || standardId > 3) ? STD_NTSC : standardId;
    int width = 720;
    int height = is_std_60hz(std_id) ? 480 : 576;

    pthread_mutex_lock(&g_io_lock);
    int rc = apply_standard_locked(fd, std_id, autoDetectFreq ? 1 : 0, colorOnly ? 1 : 0);
    pthread_mutex_unlock(&g_io_lock);

    if (rc == 0 && !colorOnly) {
        pthread_mutex_lock(&g_surface_lock);
        g_ctx.width = width;
        g_ctx.height = height;
        if (g_ctx.window) {
            ANativeWindow_setBuffersGeometry(g_ctx.window, width, height, WINDOW_FORMAT_RGBA_8888);
        }
        pthread_mutex_unlock(&g_surface_lock);
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeSetImageAdjustments(
    JNIEnv *env, jclass clazz, jint fd, jint regBrightness, jint regContrast, jint regSaturation) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    uint8_t r0a = (uint8_t) (regBrightness & 0xFF);
    uint8_t r0b = (uint8_t) (regContrast & 0x7F);
    uint8_t r0c = (uint8_t) (regSaturation & 0x7F);

    pthread_mutex_lock(&g_io_lock);
    int rc = apply_image_adjustments_locked(fd, r0a, r0b, r0c);
    pthread_mutex_unlock(&g_io_lock);

    LOGI("nativeSetImageAdjustments bri=0x%02X con=0x%02X sat=0x%02X rc=%d", r0a, r0b, r0c, rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeReadSignalStatus(
    JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    uint8_t status = 0;
    pthread_mutex_lock(&g_io_lock);
    int rc = saa7113_read_reg(fd, SAA7113_REG_STATUS, &status);
    pthread_mutex_unlock(&g_io_lock);

    return (rc == 0) ? (jint) status : (jint) rc;
}

JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeReadExtendedStatus(
    JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    uint8_t reg1f = 0;
    uint8_t reg1e = 0;
    pthread_mutex_lock(&g_io_lock);
    int rc = saa7113_read_reg(fd, SAA7113_REG_STATUS, &reg1f);
    if (rc == 0) {
        if (saa7113_read_reg(fd, SAA7113_REG_STATUS_1E, &reg1e) < 0) {
            reg1e = 0;
        }
    }
    pthread_mutex_unlock(&g_io_lock);

    return (rc == 0) ? (jint) ((((int) reg1e) << 8) | (int) reg1f) : (jint) rc;
}

/*
 * Verifica trava da subportadora de cor (bit CODE) no SAA7113/GM7113C:
 * primeiro consulta 0x1E (bit 0); se 0x1E vier zerado (SAA7113H clássico),
 * lê 0x1F com OLDSB=1 (reg 0x11 = 0x8C) e restaura 0x11 = 0x0C sob g_io_lock.
 */
JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeProbeColorLock(
    JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    uint8_t reg1e = 0;
    uint8_t oldsb_1f = 0;
    int rc = 0;
    int code_locked = 0;

    pthread_mutex_lock(&g_io_lock);
    if (saa7113_read_reg(fd, SAA7113_REG_STATUS_1E, &reg1e) == 0 && reg1e != 0 && reg1e != 0xFF) {
        code_locked = ((reg1e & 0x01) != 0 || ((reg1e >> 4) & 0x03) != 0) ? 1 : 0;
    } else {
        rc = saa7113_write_reg(fd, SAA7113_REG_OUTPUT_CNTL1, 0x8C);
        if (rc == 0) {
            rc = saa7113_read_reg(fd, SAA7113_REG_STATUS, &oldsb_1f);
            saa7113_write_reg(fd, SAA7113_REG_OUTPUT_CNTL1, 0x0C);
            if (rc == 0) {
                code_locked = (oldsb_1f & 0x01) ? 1 : 0;
            }
        }
    }
    pthread_mutex_unlock(&g_io_lock);

    return (rc == 0) ? (jint) code_locked : (jint) rc;
}

JNIEXPORT jint JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeStartStreaming(
    JNIEnv *env, jclass clazz, jint fd, jint altSetting, jint isoPacketSize,
    jint standardId, jboolean autoDetectFreq, jobject surface) {
    (void) clazz;
    if (fd < 0 || altSetting <= 0 || isoPacketSize <= 8) return -EINVAL;

    stop_streaming_internal();

    int std_id = (standardId < 0 || standardId > 3) ? STD_NTSC : standardId;
    int width = 720;
    int height = is_std_60hz(std_id) ? 480 : 576;

    pthread_mutex_lock(&g_surface_lock);
    if (g_ctx.window) {
        ANativeWindow_release(g_ctx.window);
        g_ctx.window = NULL;
    }
    if (surface) {
        g_ctx.window = ANativeWindow_fromSurface(env, surface);
        if (g_ctx.window) {
            ANativeWindow_setBuffersGeometry(g_ctx.window, width, height, WINDOW_FORMAT_RGBA_8888);
        }
    }
    g_ctx.width = width;
    g_ctx.height = height;
    pthread_mutex_unlock(&g_surface_lock);

    pthread_mutex_lock(&g_io_lock);
    int rc = apply_standard_locked(fd, std_id, autoDetectFreq ? 1 : 0, 0);
    if (rc == 0) {
        struct usbdevfs_setinterface set_alt = {
            .interface = 0,
            .altsetting = (unsigned int) altSetting
        };
        if (ioctl(fd, USBDEVFS_SETINTERFACE, &set_alt) < 0) {
            rc = -errno;
        }
    }
    pthread_mutex_unlock(&g_io_lock);

    if (rc < 0) {
        LOGE("Falha ao configurar alt=%d ou padrão=%d: rc=%d", altSetting, std_id, rc);
        return rc;
    }

    pthread_mutex_lock(&g_stream_lock);
    g_ctx.usb_fd = fd;
    g_ctx.alt_setting = altSetting;
    g_ctx.iso_packet_size = isoPacketSize;
    g_ctx.frame_pos = 0;
    g_ctx.synced_to_field = 0;
    g_ctx.current_field = 0;
    g_ctx.field_corrupted = 0;
    g_ctx.field0_ready = 0;
    g_ctx.field1_ready = 0;
    g_ctx.last_completed_field = -1;
    g_ctx.same_field_streak = 0;
    g_ctx.last_drop_log_ms = 0;

    /* Inicializa staging em preto UYVY (nunca 0x00) e preserva last_good_uyvy se mesma resolução */
    fill_uyvy_black(g_ctx.staging_uyvy, (size_t) width * (size_t) height * 2);
    pthread_mutex_lock(&g_frame_lock);
    if (g_ctx.last_good_w != width || g_ctx.last_good_h != height) {
        fill_uyvy_black(g_ctx.last_good_uyvy, (size_t) width * (size_t) height * 2);
        g_ctx.has_good_frame = 0;
        g_ctx.last_good_w = width;
        g_ctx.last_good_h = height;
    }
    pthread_mutex_unlock(&g_frame_lock);

    atomic_store(&g_ctx.stalled, 0);
    atomic_store(&g_ctx.last_frame_mono_ms, 0);
    atomic_store(&g_ctx.running, 1);

    /* pthread_create devolve o código de erro e NÃO altera errno. */
    int create_err = pthread_create(&g_ctx.iso_thread, NULL, iso_stream_thread, NULL);
    if (create_err == 0) {
        g_ctx.thread_active = 1;
        rc = 0;
    } else {
        atomic_store(&g_ctx.running, 0);
        rc = -create_err;
        LOGE("pthread_create failed: %d", create_err);
        struct usbdevfs_setinterface set_zero = {.interface = 0, .altsetting = 0};
        ioctl(fd, USBDEVFS_SETINTERFACE, &set_zero);
    }
    pthread_mutex_unlock(&g_stream_lock);

    return rc;
}

JNIEXPORT jboolean JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeIsStreaming(
    JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return atomic_load(&g_ctx.running) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Retorna [framesRendered, framesDropped, isoPacketErrors, lastFrameAgeMs, stalledFlag]
 * para o watchdog e diagnóstico do controlador Kotlin.
 */
JNIEXPORT jboolean JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeGetStreamStats(
    JNIEnv *env, jclass clazz, jlongArray outStats5) {
    (void) clazz;
    if (!outStats5 || (*env)->GetArrayLength(env, outStats5) < 5) return JNI_FALSE;

    const int64_t last_ms = atomic_load(&g_ctx.last_frame_mono_ms);
    const int64_t age_ms = (last_ms > 0) ? (mono_time_ms() - last_ms) : -1LL;
    jlong vals[5] = {
        (jlong) atomic_load(&g_ctx.frames_rendered),
        (jlong) atomic_load(&g_ctx.frames_dropped),
        (jlong) atomic_load(&g_ctx.iso_packet_errors),
        (jlong) age_ms,
        (jlong) (atomic_load(&g_ctx.stalled) ? 1 : 0)
    };
    (*env)->SetLongArrayRegion(env, outStats5, 0, 5, vals);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeUpdateSurface(
    JNIEnv *env, jclass clazz, jobject surface) {
    (void) clazz;
    ANativeWindow *new_win = surface ? ANativeWindow_fromSurface(env, surface) : NULL;

    pthread_mutex_lock(&g_surface_lock);
    if (g_ctx.window) {
        ANativeWindow_release(g_ctx.window);
    }
    g_ctx.window = new_win;
    int w = g_ctx.width;
    int h = g_ctx.height;
    if (g_ctx.window) {
        ANativeWindow_setBuffersGeometry(g_ctx.window, w, h, WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g_surface_lock);

    /* Redesenha imediatamente o último quadro bom para evitar piscada preta/verde */
    if (new_win) {
        pthread_mutex_lock(&g_frame_lock);
        if (g_ctx.has_good_frame && g_ctx.last_good_w == w && g_ctx.last_good_h == h) {
            render_uyvy_frame_to_window(g_ctx.last_good_uyvy, w, h);
        }
        pthread_mutex_unlock(&g_frame_lock);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeCopyLastLumaGrid(
    JNIEnv *env, jclass clazz, jintArray out192) {
    (void) clazz;
    if (!out192 || !atomic_load(&g_ctx.luma_valid)) return JNI_FALSE;
    if ((*env)->GetArrayLength(env, out192) < 192) return JNI_FALSE;

    jint buf[192];
    pthread_mutex_lock(&g_luma_lock);
    int valid = atomic_load(&g_ctx.luma_valid);
    if (valid) {
        for (int i = 0; i < 192; i++) {
            buf[i] = (jint) g_ctx.last_luma192[i];
        }
    }
    pthread_mutex_unlock(&g_luma_lock);

    if (!valid) return JNI_FALSE;
    (*env)->SetIntArrayRegion(env, out192, 0, 192, buf);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeStopStreaming(
    JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    stop_streaming_internal();
}

JNIEXPORT void JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeReleaseInterface(
    JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    stop_streaming_internal();

    if (fd >= 0) {
        struct usbdevfs_setinterface set_zero = {.interface = 0, .altsetting = 0};
        ioctl(fd, USBDEVFS_SETINTERFACE, &set_zero);
    }

    pthread_mutex_lock(&g_surface_lock);
    if (g_ctx.window) {
        ANativeWindow_release(g_ctx.window);
        g_ctx.window = NULL;
    }
    pthread_mutex_unlock(&g_surface_lock);

    pthread_mutex_lock(&g_frame_lock);
    g_ctx.has_good_frame = 0;
    pthread_mutex_unlock(&g_frame_lock);
}
