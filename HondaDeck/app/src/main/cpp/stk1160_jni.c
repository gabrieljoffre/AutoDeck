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
#define SAA7113_REG_STATUS 0x1F

#define STK1160_ISO_EP 0x82
#define NUM_ISO_URBS 8
#define PACKETS_PER_URB 32
#define MAX_FRAME_BYTES (720 * 576 * 2)

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

static const regval_t STK1160_STD525_REGS[] = {
    {0x110, 0x0000}, {0x111, 0x0000}, {0x112, 0x0003}, {0x113, 0x0000},
    {0x114, 0x05a0}, {0x115, 0x0005}, {0x116, 0x00f3}, {0x117, 0x0000}
};

static const regval_t STK1160_STD625_REGS[] = {
    {0x110, 0x0000}, {0x111, 0x0000}, {0x112, 0x0001}, {0x113, 0x0000},
    {0x114, 0x05a0}, {0x115, 0x0005}, {0x116, 0x0121}, {0x117, 0x0001}
};

/* gm7113c_init registers 0x01..0x17 */
static const uint8_t GM7113C_INIT_VALS[23] = {
    0x08, 0xc0, 0x33, 0x00, 0x00, 0xe9, 0x0d, 0x98,
    0x01, 0x80, 0x47, 0x40, 0x00, 0x01, 0x2a, 0x00,
    0x0c, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00
};

static pthread_mutex_t g_io_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_surface_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_stream_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_luma_lock = PTHREAD_MUTEX_INITIALIZER;

static struct {
    atomic_bool running;
    atomic_bool luma_valid;
    int thread_active;
    pthread_t iso_thread;
    int usb_fd;
    int alt_setting;
    int iso_packet_size;
    int width;
    int height;
    ANativeWindow *window;
    uint8_t uyvy_frame[MAX_FRAME_BYTES];
    uint8_t last_luma192[192];
    size_t frame_pos;
    int odd_field;
    int frame_has_data;
} g_ctx = {
    .running = 0,
    .luma_valid = 0,
    .thread_active = 0,
    .usb_fd = -1,
    .alt_setting = 5,
    .iso_packet_size = 3072,
    .width = 720,
    .height = 480,
    .window = NULL,
    .frame_pos = 0,
    .odd_field = 1,
    .frame_has_data = 0
};

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

static int apply_standard_locked(int fd, int is_ntsc) {
    int rc;
    if (is_ntsc) {
        rc = stk1160_write_table(fd, STK1160_STD525_REGS,
                                 sizeof(STK1160_STD525_REGS) / sizeof(STK1160_STD525_REGS[0]));
        if (rc < 0) return rc;
        rc = saa7113_write_reg(fd, 0x08, 0x68);
        if (rc < 0) return rc;
    } else {
        rc = stk1160_write_table(fd, STK1160_STD625_REGS,
                                 sizeof(STK1160_STD625_REGS) / sizeof(STK1160_STD625_REGS[0]));
        if (rc < 0) return rc;
        rc = saa7113_write_reg(fd, 0x08, 0x28);
        if (rc < 0) return rc;
    }
    rc = saa7113_write_reg(fd, 0x0e, 0x01);
    if (rc < 0) return rc;
    return saa7113_write_reg(fd, 0x02, 0xc0);
}

static inline uint8_t clamp_u8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return (uint8_t) v;
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

static void render_uyvy_frame_to_window(const uint8_t *uyvy, int width, int height) {
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
            pthread_mutex_unlock(&g_surface_lock);
            return;
        }

        const int src_stride = width * 2;
        const int copy_w = (width < buf.width) ? width : buf.width;
        const int copy_h = (height < buf.height) ? height : buf.height;

        for (int y = 0; y < copy_h; y++) {
            const uint8_t *src = uyvy + y * src_stride;
            uint32_t *dst = ((uint32_t *) buf.bits) + y * buf.stride;

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
        }
        ANativeWindow_unlockAndPost(win);
    }
    pthread_mutex_unlock(&g_surface_lock);
}

static void process_stk1160_packet(const uint8_t *p, int len, int stream_w, int stream_h) {
    if (len < 4) return;

    const uint8_t hdr = p[0];
    if (hdr == 0xc0) {
        if (g_ctx.frame_has_data) {
            render_uyvy_frame_to_window(g_ctx.uyvy_frame, stream_w, stream_h);
            g_ctx.frame_has_data = 0;
        }
        g_ctx.odd_field = (hdr & 0x40) ? 1 : 0;
        g_ctx.frame_pos = 0;
    } else if (hdr == 0x80) {
        g_ctx.odd_field = (hdr & 0x40) ? 1 : 0;
        g_ctx.frame_pos = 0;
    }

    p += 4;
    len -= 4;
    if (len <= 0) return;

    const size_t width = (size_t) stream_w;
    const size_t height = (size_t) stream_h;
    const size_t bytesperline = width * 2;
    const size_t field_lines = height / 2;
    const size_t frame_bytes = bytesperline * height;
    const size_t field_offset = g_ctx.odd_field ? 0 : bytesperline;

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

        memcpy(g_ctx.uyvy_frame + dst_offset, p, to_copy);
        p += to_copy;
        len -= (int) to_copy;
        g_ctx.frame_pos += to_copy;
        g_ctx.frame_has_data = 1;
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
                LOGE("USBDEVFS_SUBMITURB %d failed: %d", i, errno);
                break;
            }
        }
    }

    if (submit_ok > 0) {
        pthread_mutex_lock(&g_io_lock);
        usb_ctrl_write_reg(fd, STK1160_DCTRL + 3, 0x00);
        usb_ctrl_write_reg(fd, STK1160_DCTRL, 0xb3);
        pthread_mutex_unlock(&g_io_lock);
        LOGI("STK1160 ISO stream started (%dx%d alt=%d pkt=%d)",
             stream_w, stream_h, g_ctx.alt_setting, pkt_size);
    } else {
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
            break;
        }
        if (pfd.revents & (POLLERR | POLLHUP)) {
            LOGW("USB fd disconnected during poll");
            break;
        }

        while (atomic_load(&g_ctx.running)) {
            struct usbdevfs_urb *reaped = NULL;
            int rrc = ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped);
            if (rrc < 0 || !reaped) {
                if (errno == EAGAIN || errno == EINTR) break;
                if (errno == ENODEV) {
                    atomic_store(&g_ctx.running, 0);
                    break;
                }
                break;
            }

            const uint8_t *base = (const uint8_t *) reaped->buffer;
            for (int p = 0; p < reaped->number_of_packets; p++) {
                const struct usbdevfs_iso_packet_desc *desc = &reaped->iso_frame_desc[p];
                if (desc->status == 0 && desc->actual_length > 4) {
                    process_stk1160_packet(base + (size_t) p * pkt_size,
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

    LOGI("STK1160 ISO stream stopped and AltSetting restored to 0");
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
    JNIEnv *env, jclass clazz, jint fd, jint channel, jboolean isNtsc) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    int ch = (channel < 0) ? 0 : (channel > 3 ? 3 : channel);

    pthread_mutex_lock(&g_io_lock);
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
        rc = apply_standard_locked(fd, isNtsc ? 1 : 0);
    }
    pthread_mutex_unlock(&g_io_lock);

    LOGI("nativeInitChip ch=%d ntsc=%d rc=%d", ch, isNtsc ? 1 : 0, rc);
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
    JNIEnv *env, jclass clazz, jint fd, jboolean isNtsc) {
    (void) env;
    (void) clazz;
    if (fd < 0) return -EINVAL;

    int width = 720;
    int height = isNtsc ? 480 : 576;

    pthread_mutex_lock(&g_io_lock);
    int rc = apply_standard_locked(fd, isNtsc ? 1 : 0);
    pthread_mutex_unlock(&g_io_lock);

    if (rc == 0) {
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
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeStartStreaming(
    JNIEnv *env, jclass clazz, jint fd, jint altSetting, jint isoPacketSize, jboolean isNtsc, jobject surface) {
    (void) clazz;
    if (fd < 0 || altSetting <= 0 || isoPacketSize <= 4) return -EINVAL;

    stop_streaming_internal();

    int width = 720;
    int height = isNtsc ? 480 : 576;

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
    int rc = apply_standard_locked(fd, isNtsc ? 1 : 0);
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
        LOGE("Failed setting alt=%d or std: %d", altSetting, rc);
        return rc;
    }

    pthread_mutex_lock(&g_stream_lock);
    g_ctx.usb_fd = fd;
    g_ctx.alt_setting = altSetting;
    g_ctx.iso_packet_size = isoPacketSize;
    g_ctx.frame_pos = 0;
    g_ctx.odd_field = 1;
    g_ctx.frame_has_data = 0;
    memset(g_ctx.uyvy_frame, 0, sizeof(g_ctx.uyvy_frame));
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

/*
 * A thread ISO encerra sozinha quando o envio de URBs falha (ex.: banda do hub USB),
 * quando o poll dá erro ou quando o dispositivo some. Este getter permite ao Kotlin
 * perceber isso e reiniciar a captura, em vez de ficar com a tela preta.
 */
JNIEXPORT jboolean JNICALL
Java_com_autodeck_headunit_hardware_Stk1160NativeBridge_nativeIsStreaming(
    JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return atomic_load(&g_ctx.running) ? JNI_TRUE : JNI_FALSE;
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
    if (g_ctx.window) {
        ANativeWindow_setBuffersGeometry(g_ctx.window, g_ctx.width, g_ctx.height, WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g_surface_lock);
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
}
