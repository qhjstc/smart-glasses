#include "tcp_client_stream.h"

#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netdb.h>
#include <arpa/inet.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "esp_mac.h"
#include "esp_crc.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "cam_ov2640.h"

static const char *TAG = "tcp_client_stream";

/* ---- Protocol ---- */
#define MAGIC_H 0x48505345u  // 'ESPH'
#define MAGIC_F 0x46505345u  // 'ESPF'
#define VER     1
#define TYPE_HELLO 1
#define TYPE_FRAME 2
#define FLAG_JPEG  1

typedef struct __attribute__((packed)) {
    uint32_t magic;      // MAGIC_H
    uint16_t version;    // VER
    uint16_t type;       // TYPE_HELLO
    uint32_t device_id;
    uint32_t reserved;
} hello_msg_t;

typedef struct __attribute__((packed)) {
    uint32_t magic;       // MAGIC_F
    uint16_t version;     // VER
    uint16_t type;        // TYPE_FRAME
    uint32_t device_id;
    uint32_t seq;
    uint64_t ts_us;       // esp_timer_get_time()
    uint32_t payload_len; // fb->len
    uint32_t crc32;       // payload crc32
    uint32_t flags;       // FLAG_JPEG
} frame_hdr_t;

typedef struct {
    tcp_stream_cfg_t cfg;
    uint32_t device_id;
} stream_ctx_t;

static int send_all(int fd, const void *buf, size_t len)
{
    const uint8_t *p = (const uint8_t *)buf;
    while (len) {
        int n = send(fd, p, len, 0);
        if (n < 0) {
            ESP_LOGE(TAG, "send() failed errno=%d", errno);
            return -1;
        }
        if (n == 0) {
            ESP_LOGE(TAG, "send() returned 0");
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static uint32_t make_device_id_from_mac(void)
{
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    // 用后 4 字节拼成 u32（稳定且不易冲突）
    return ((uint32_t)mac[2] << 24) | ((uint32_t)mac[3] << 16) | ((uint32_t)mac[4] << 8) | (uint32_t)mac[5];
}

static int connect_to_server(const char *ip, uint16_t port, int timeout_ms)
{
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (fd < 0) {
        ESP_LOGE(TAG, "socket() failed errno=%d", errno);
        return -1;
    }

    struct timeval tv = {
        .tv_sec = timeout_ms / 1000,
        .tv_usec = (timeout_ms % 1000) * 1000
    };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    if (inet_pton(AF_INET, ip, &addr.sin_addr) != 1) {
        ESP_LOGE(TAG, "inet_pton failed for %s", ip);
        close(fd);
        return -1;
    }

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        ESP_LOGE(TAG, "connect(%s:%u) failed errno=%d", ip, port, errno);
        close(fd);
        return -1;
    }

    return fd;
}

static void stream_task(void *arg)
{
    stream_ctx_t *ctx = (stream_ctx_t *)arg;

    int backoff = ctx->cfg.reconnect_delay_ms > 0 ? ctx->cfg.reconnect_delay_ms : 500;
    int max_backoff = ctx->cfg.max_backoff_ms > 0 ? ctx->cfg.max_backoff_ms : 5000;
    int timeout_ms = ctx->cfg.send_timeout_ms > 0 ? ctx->cfg.send_timeout_ms : 5000;

    uint32_t seq = 0;

    while (1) {
        ESP_LOGI(TAG, "connecting to %s:%u ...", ctx->cfg.server_ip, ctx->cfg.server_port);
        int fd = connect_to_server(ctx->cfg.server_ip, ctx->cfg.server_port, timeout_ms);
        if (fd < 0) {
            ESP_LOGW(TAG, "connect failed, retry in %d ms", backoff);
            vTaskDelay(pdMS_TO_TICKS(backoff));
            backoff = (backoff * 2 > max_backoff) ? max_backoff : backoff * 2;
            continue;
        }

        backoff = ctx->cfg.reconnect_delay_ms > 0 ? ctx->cfg.reconnect_delay_ms : 500;

        // Send HELLO
        hello_msg_t hello = {
            .magic = MAGIC_H,
            .version = VER,
            .type = TYPE_HELLO,
            .device_id = ctx->device_id,
            .reserved = 0
        };

        if (send_all(fd, &hello, sizeof(hello)) != 0) {
            ESP_LOGW(TAG, "send HELLO failed");
            close(fd);
            continue;
        }

        ESP_LOGI(TAG, "connected, streaming... device_id=%u", (unsigned)ctx->device_id);

        while (1) {
            camera_fb_t *fb = cam_ov2640_fb_get();
            if (!fb) {
                vTaskDelay(pdMS_TO_TICKS(5));
                continue;
            }

            // 你初始化用了 jpeg_quality，所以一般 fb->format 会是 JPEG
            uint64_t ts_us = (uint64_t)esp_timer_get_time();
            uint32_t crc = esp_crc32_le(0, fb->buf, fb->len);

            frame_hdr_t h = {
                .magic = MAGIC_F,
                .version = VER,
                .type = TYPE_FRAME,
                .device_id = ctx->device_id,
                .seq = seq++,
                .ts_us = ts_us,
                .payload_len = fb->len,
                .crc32 = crc,
                .flags = FLAG_JPEG
            };

            int ok = (send_all(fd, &h, sizeof(h)) == 0) &&
                     (send_all(fd, fb->buf, fb->len) == 0);

            cam_ov2640_fb_return(fb);

            if (!ok) {
                ESP_LOGW(TAG, "send frame failed, reconnecting...");
                break;
            }

            // 可选：限制帧率，避免占满带宽/CPU（按需调整）
            // vTaskDelay(pdMS_TO_TICKS(10));
        }

        close(fd);
        vTaskDelay(pdMS_TO_TICKS(backoff));
        backoff = (backoff * 2 > max_backoff) ? max_backoff : backoff * 2;
    }
}

esp_err_t tcp_client_stream_start(const tcp_stream_cfg_t *cfg)
{
    if (!cfg || !cfg->server_ip || cfg->server_port == 0) return ESP_ERR_INVALID_ARG;

    stream_ctx_t *ctx = (stream_ctx_t *)calloc(1, sizeof(stream_ctx_t));
    if (!ctx) return ESP_ERR_NO_MEM;

    ctx->cfg = *cfg;
    ctx->device_id = (cfg->device_id != 0) ? cfg->device_id : make_device_id_from_mac();

    BaseType_t ok = xTaskCreate(stream_task, "tcp_stream", 8192, ctx, 5, NULL);
    if (ok != pdPASS) {
        free(ctx);
        return ESP_FAIL;
    }
    return ESP_OK;
}