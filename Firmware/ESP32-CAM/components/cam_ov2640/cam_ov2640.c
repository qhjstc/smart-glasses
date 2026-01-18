#include "cam_ov2640.h"

#include "esp_log.h"
#include "esp_check.h"
#include "sdkconfig.h"

static const char *TAG = "cam_ov2640";

static bool s_inited = false;

/* AI-Thinker ESP32-CAM pin map (OV2640) */
#define CAM_PIN_PWDN    32
#define CAM_PIN_RESET   -1
#define CAM_PIN_XCLK     0
#define CAM_PIN_SIOD    26
#define CAM_PIN_SIOC    27

#define CAM_PIN_D7      35
#define CAM_PIN_D6      34
#define CAM_PIN_D5      39
#define CAM_PIN_D4      36
#define CAM_PIN_D3      21
#define CAM_PIN_D2      19
#define CAM_PIN_D1      18
#define CAM_PIN_D0       5
#define CAM_PIN_VSYNC   25
#define CAM_PIN_HREF    23
#define CAM_PIN_PCLK    22

static esp_err_t apply_default_sensor_settings(void)
{
    sensor_t *s = esp_camera_sensor_get();
    if (!s) return ESP_FAIL;

    // Optional defaults (keep conservative)
    // s->set_framesize(s, FRAMESIZE_QVGA); // framesize is set by config already
    s->set_brightness(s, 0);
    s->set_contrast(s, 0);
    s->set_saturation(s, 0);
    s->set_whitebal(s, 1);
    s->set_awb_gain(s, 1);
    s->set_exposure_ctrl(s, 1);
    s->set_aec2(s, 1);
    s->set_ae_level(s, 0);
    s->set_gain_ctrl(s, 1);
    s->set_agc_gain(s, 0);
    s->set_gainceiling(s, (gainceiling_t)0);

    return ESP_OK;
}

esp_err_t cam_ov2640_init(const cam_ov2640_cfg_t *cfg)
{
    ESP_RETURN_ON_FALSE(cfg, ESP_ERR_INVALID_ARG, TAG, "cfg is null");
    ESP_RETURN_ON_FALSE(!s_inited, ESP_ERR_INVALID_STATE, TAG, "already inited");

#if !CONFIG_ESP32_SPIRAM_SUPPORT
    ESP_LOGW(TAG, "PSRAM is disabled in sdkconfig; high resolutions may fail");
#endif

    camera_config_t c = {0};
    c.ledc_channel = LEDC_CHANNEL_0;
    c.ledc_timer   = LEDC_TIMER_0;

    c.pin_d0 = CAM_PIN_D0;
    c.pin_d1 = CAM_PIN_D1;
    c.pin_d2 = CAM_PIN_D2;
    c.pin_d3 = CAM_PIN_D3;
    c.pin_d4 = CAM_PIN_D4;
    c.pin_d5 = CAM_PIN_D5;
    c.pin_d6 = CAM_PIN_D6;
    c.pin_d7 = CAM_PIN_D7;

    c.pin_xclk  = CAM_PIN_XCLK;
    c.pin_pclk  = CAM_PIN_PCLK;
    c.pin_vsync = CAM_PIN_VSYNC;
    c.pin_href  = CAM_PIN_HREF;

    c.pin_sccb_sda = CAM_PIN_SIOD;
    c.pin_sccb_scl = CAM_PIN_SIOC;

    c.pin_pwdn  = CAM_PIN_PWDN;
    c.pin_reset = CAM_PIN_RESET;

    c.xclk_freq_hz = 20000000;
    c.pixel_format = PIXFORMAT_JPEG;

    c.frame_size   = cfg->framesize;
    c.jpeg_quality = cfg->jpeg_quality;
    c.fb_count     = cfg->fb_count;

    // Latency control: grab mode
    c.grab_mode = cfg->grab_latest ? CAMERA_GRAB_LATEST : CAMERA_GRAB_WHEN_EMPTY;

#if CONFIG_ESP32_SPIRAM_SUPPORT
    // Prefer PSRAM for frame buffers if available
    c.fb_location = CAMERA_FB_IN_PSRAM;
#else
    c.fb_location = CAMERA_FB_IN_DRAM;
#endif

    esp_err_t err = esp_camera_init(&c);
    ESP_RETURN_ON_ERROR(err, TAG, "esp_camera_init failed: %s", esp_err_to_name(err));

    err = apply_default_sensor_settings();
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "apply_default_sensor_settings failed");
    }

    s_inited = true;

    // Quick sanity grab
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
        ESP_LOGE(TAG, "fb_get failed right after init");
        esp_camera_deinit();
        s_inited = false;
        return ESP_FAIL;
    }
    ESP_LOGI(TAG, "Camera OK: %dx%d, len=%u, format=%d",
             fb->width, fb->height, (unsigned)fb->len, fb->format);
    esp_camera_fb_return(fb);

    return ESP_OK;
}

esp_err_t cam_ov2640_deinit(void)
{
    ESP_RETURN_ON_FALSE(s_inited, ESP_ERR_INVALID_STATE, TAG, "not inited");
    esp_err_t err = esp_camera_deinit();
    s_inited = false;
    return err;
}

camera_fb_t *cam_ov2640_fb_get(void)
{
    if (!s_inited) return NULL;
    return esp_camera_fb_get();
}

void cam_ov2640_fb_return(camera_fb_t *fb)
{
    if (!fb) return;
    esp_camera_fb_return(fb);
}

esp_err_t cam_ov2640_set_vflip(int vflip)
{
    ESP_RETURN_ON_FALSE(s_inited, ESP_ERR_INVALID_STATE, TAG, "not inited");
    sensor_t *s = esp_camera_sensor_get();
    ESP_RETURN_ON_FALSE(s, ESP_FAIL, TAG, "sensor null");
    return (s->set_vflip(s, vflip) == 0) ? ESP_OK : ESP_FAIL;
}

esp_err_t cam_ov2640_set_hmirror(int hmirror)
{
    ESP_RETURN_ON_FALSE(s_inited, ESP_ERR_INVALID_STATE, TAG, "not inited");
    sensor_t *s = esp_camera_sensor_get();
    ESP_RETURN_ON_FALSE(s, ESP_FAIL, TAG, "sensor null");
    return (s->set_hmirror(s, hmirror) == 0) ? ESP_OK : ESP_FAIL;
}