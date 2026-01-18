#include <string.h>
#include "esp_log.h"
#include "esp_http_server.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "cam_ov2640.h"

static const char *TAG = "web";

#define PART_BOUNDARY "123456789000000000000987654321"
static const char *STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char *STREAM_PART_HDR  = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static esp_err_t root_get_handler(httpd_req_t *req)
{
    const char *html =
        "<!doctype html><html><head><meta charset='utf-8'/>"
        "<title>ESP32-CAM</title></head><body>"
        "<h3>MJPEG Stream</h3>"
        "<img src='/stream' style='width:100%;max-width:480px;'/>"
        "</body></html>";
    httpd_resp_set_type(req, "text/html");
    return httpd_resp_send(req, html, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t stream_get_handler(httpd_req_t *req)
{
    esp_err_t err = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
    if (err != ESP_OK) return err;

    // 建议禁止缓存
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
    httpd_resp_set_hdr(req, "Pragma", "no-cache");

    while (1) {
        // 从摄像头取一帧（JPEG）
        // ---- 这两行按你的 cam_ov2640 API 改名 ----
        camera_fb_t *fb = cam_ov2640_fb_get();     // 需要返回 fb->buf / fb->len
        if (!fb) {
            ESP_LOGW(TAG, "fb_get failed");
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }

        // 发送分隔符
        err = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));
        if (err != ESP_OK) { cam_ov2640_fb_return(fb); break; }

        // 发送本帧头
        char hdr[64];
        int hlen = snprintf(hdr, sizeof(hdr), STREAM_PART_HDR, (unsigned)fb->len);
        err = httpd_resp_send_chunk(req, hdr, hlen);
        if (err != ESP_OK) { cam_ov2640_fb_return(fb); break; }

        // 发送 JPEG 数据
        err = httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len);
        cam_ov2640_fb_return(fb);
        if (err != ESP_OK) break;

        // 控制帧率（按需调）
        vTaskDelay(pdMS_TO_TICKS(30));
    }

    // 结束 chunked 传输
    httpd_resp_send_chunk(req, NULL, 0);
    ESP_LOGI(TAG, "stream client disconnected");
    return ESP_OK;
}

void start_webserver(void)
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = 80;
    config.ctrl_port = 32768;          // 避免端口冲突（可不改）
    config.stack_size = 8192;          // stream handler 需要更大栈
    config.recv_wait_timeout = 10;
    config.send_wait_timeout = 10;

    httpd_handle_t server = NULL;
    esp_err_t err = httpd_start(&server, &config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "httpd_start failed: %s", esp_err_to_name(err));
        return;
    }

    httpd_uri_t uri_root = {
        .uri = "/",
        .method = HTTP_GET,
        .handler = root_get_handler,
        .user_ctx = NULL
    };
    httpd_register_uri_handler(server, &uri_root);

    httpd_uri_t uri_stream = {
        .uri = "/stream",
        .method = HTTP_GET,
        .handler = stream_get_handler,
        .user_ctx = NULL
    };
    httpd_register_uri_handler(server, &uri_stream);

    ESP_LOGI(TAG, "Webserver started: /  and  /stream");
}